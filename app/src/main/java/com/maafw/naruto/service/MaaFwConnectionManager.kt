package com.maafw.naruto.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.maafw.naruto.IRemoteEngineService
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.remote.RemoteEngineServiceImpl
import com.maafw.naruto.root.RootRemoteServiceConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * MAAFW 引擎连接状态机（方案 4：统一绑定入口，根治并发双引擎）。
 *
 * 状态：Disconnected / Connecting / Connected / Died / Error
 * - [bind] 统一入口：Shizuku 三方案 + Root（liblauncher + ContentProvider），随机 tag + 递增 version；
 * - [useRemoteService] 业务统一入口：拿服务或先 bind 再等；
 * - binder 死亡自动感知（linkToDeath）→ Died → 自动重绑；
 * - 主动解绑（[unbind] / manualUnbind）不触发重连；
 * - 连接成功后：喂心跳、写入共享引擎（EngineConnectionShared）、通知 [onConnected]。
 */
object MaaFwConnectionManager {

    private const val TAG = "MaaFwConnectionManager"
    private const val BIND_TIMEOUT_MS = 15_000L
    private const val REBIND_DELAY_MS = 3_000L

    sealed class State {
        object Disconnected : State()
        object Connecting : State()
        object Died : State()
        data class Connected(val svc: IRemoteEngineService) : State()
        data class Error(val e: Throwable) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var connectedListener: ((IRemoteEngineService) -> Unit)? = null
    @Volatile private var stateChangedListener: ((State) -> Unit)? = null

    private val lock = Any()
    private var currentConnection: ServiceConnection? = null
    private var currentArgs: Shizuku.UserServiceArgs? = null
    private val shizukuServiceVersion = AtomicInteger(100)
    @Volatile private var manualUnbind = false
    @Volatile private var rebinding = false

    // ───────────── 初始化 / 获取 ─────────────

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isConnected(): Boolean = _state.value is State.Connected

    val currentService: IRemoteEngineService?
        get() = (_state.value as? State.Connected)?.svc

    /** 连接成功回调（MainActivity 注入：注册监听/设置 UI 等） */
    fun setOnConnected(listener: ((IRemoteEngineService) -> Unit)?) {
        connectedListener = listener
    }

    /** 状态变化回调（UI/统一收尾观察） */
    fun setOnStateChanged(listener: ((State) -> Unit)?) {
        stateChangedListener = listener
    }

    // ───────────── 统一绑定入口 ─────────────

    /** 绑定引擎（Root 模式走 liblauncher + ContentProvider；否则 Shizuku 三方案） */
    fun bind() {
        val ctx = appContext ?: return
        synchronized(lock) {
            if (_state.value is State.Connecting) return
            if (_state.value is State.Connected) return  // 已连接：幂等，不重绑（避免中断任务/重复起引擎）
            cleanupStaleBinding()
            _state.value = State.Connecting
            notifyStateChanged()
            manualUnbind = false
        }
        if (SettingsRepository.isRootMode(ctx)) {
            bindRoot(ctx)
        } else {
            bindShizuku(ctx)
        }
    }

    /** 主动解绑（App 退出/停止时），不触发自动重连 */
    fun unbind() {
        manualUnbind = true
        cleanupStaleBinding()
        if (_state.value is State.Connected) {
            _state.value = State.Disconnected
            notifyStateChanged()
        }
    }

    /** 业务统一入口：拿服务或先绑定再等（15s） */
    suspend fun <T> useRemoteService(block: (IRemoteEngineService) -> T): T? {
        val svc = currentService ?: run {
            bind()
            waitConnected(BIND_TIMEOUT_MS)
        } ?: return null
        return runCatching { block(svc) }.getOrNull()
    }

    // ───────────── Shizuku 绑定（三方案，从 MainActivity 迁移） ─────────────

    private fun bindShizuku(ctx: Context) {
        if (!Shizuku.pingBinder()) {
            setError(IllegalStateException("Shizuku 未运行"))
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            setError(IllegalStateException("Shizuku 未授权"))
            return
        }
        val planA = Shizuku.UserServiceArgs(ComponentName(ctx, RemoteEngineServiceImpl::class.java))
            .daemon(false).processNameSuffix("remote_engine").debuggable(true)
            .tag(UUID.randomUUID().toString()).version(shizukuServiceVersion.incrementAndGet())
        val planB = Shizuku.UserServiceArgs(ComponentName(ctx, RemoteEngineServiceImpl::class.java))
            .daemon(true).processNameSuffix("remote_engine")
            .tag(UUID.randomUUID().toString()).version(shizukuServiceVersion.incrementAndGet())
        val planC = Shizuku.UserServiceArgs(ComponentName(ctx, RemoteEngineServiceImpl::class.java))
            .daemon(false).processNameSuffix("engine").debuggable(true)
            .tag(UUID.randomUUID().toString()).version(shizukuServiceVersion.incrementAndGet())

        kotlinx.coroutines.GlobalScope.launch {
            for ((_, args) in listOf("方案A" to planA, "方案B" to planB, "方案C" to planC)) {
                if (_state.value is State.Connected) break
                if (tryBind(args)) break
                cleanupStaleBinding()
            }
            if (_state.value !is State.Connected) {
                // 绑定失败：Root 可用才切 Root；否则 Error
                val rootOk = com.maafw.naruto.root.RootManager.isRootAvailable()
                if (rootOk && SettingsRepository.isRootMode(ctx).not()) {
                    Log.w(TAG, "Shizuku 三方案超时，设备可 root，切 Root 模式")
                    SettingsRepository.setRunMode(ctx, SettingsRepository.RUN_MODE_ROOT)
                    bind()
                } else {
                    setError(IllegalStateException("Shizuku 绑定超时"))
                }
            }
        }
    }

    private suspend fun tryBind(args: Shizuku.UserServiceArgs): Boolean {
        val conn = newServiceConnection()
        synchronized(lock) {
            currentConnection = conn
            currentArgs = args
        }
        runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure {
                synchronized(lock) { currentConnection = null; currentArgs = null }
                return false
            }
        // 等 5s（每 100ms 检查）
        repeat(50) {
            if (_state.value is State.Connected) return true
            delay(100)
        }
        return _state.value is State.Connected
    }

    private fun newServiceConnection(): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                currentConnection = null
                currentArgs = null
            }
            onConnected(IRemoteEngineService.Stub.asInterface(service))
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (manualUnbind) { manualUnbind = false; return }
            Log.w(TAG, "引擎服务已断开")
            if (_state.value is State.Connected) {
                _state.value = State.Died
                notifyStateChanged()
                scheduleRebind()
            }
        }
    }

    // ───────────── Root 绑定（liblauncher + ContentProvider） ─────────────

    private fun bindRoot(ctx: Context) {
        RootRemoteServiceConnector.initialize(ctx)
        RootRemoteServiceConnector.bind(
            onConnected = { binder ->
                onConnected(IRemoteEngineService.Stub.asInterface(binder))
            },
            onError = { e -> setError(e) }
        )
    }

    // ───────────── 连接成功 / 失败 / 重连 ─────────────

    private fun onConnected(svc: IRemoteEngineService?) {
        if (svc == null) { setError(IllegalStateException("引擎 binder 为空")); return }
        // 心跳：喂 App pid（引擎看门狗守护）
        runCatching { svc.heartbeat(android.os.Process.myPid()) }
        // 写入共享引擎（定时任务复用）
        EngineConnectionShared.service = svc
        EngineConnectionShared.bound = true
        EngineConnectionShared.owner = "main"
        EngineConnectionShared.engineMode =
            if (SettingsRepository.isRootMode(appContext ?: return)) "root" else "shizuku"
        // linkToDeath：引擎进程死亡自动感知
        registerDeathWatcher(svc.asBinder())
        _state.value = State.Connected(svc)
        notifyStateChanged()
        Log.i(TAG, "引擎已连接")
        connectedListener?.invoke(svc)
    }

    private fun registerDeathWatcher(binder: IBinder?) {
        if (binder == null) return
        runCatching {
            val recipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    binder.unlinkToDeath(this, 0)
                    if (manualUnbind) { manualUnbind = false; return }
                    Log.w(TAG, "引擎进程死亡（binderDied）")
                    _state.value = State.Died
                    notifyStateChanged()
                    scheduleRebind()
                }
            }
            binder.linkToDeath(recipient, 0)
        }
    }

    private fun scheduleRebind() {
        if (manualUnbind || rebinding) return
        rebinding = true
        kotlinx.coroutines.GlobalScope.launch {
            delay(REBIND_DELAY_MS)
            rebinding = false
            if (!manualUnbind && _state.value !is State.Connected) {
                Log.i(TAG, "自动重连…")
                bind()
            }
        }
    }

    private fun setError(e: Throwable) {
        if (manualUnbind) { manualUnbind = false; return }
        _state.value = State.Error(e)
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        stateChangedListener?.invoke(_state.value)
    }

    /** 清理旧绑定（unbind remove=true，销毁服务端 record） */
    private fun cleanupStaleBinding() {
        synchronized(lock) {
            val conn = currentConnection
            val args = currentArgs
            if (conn != null && args != null) {
                runCatching { Shizuku.unbindUserService(args, conn, true) }
            }
            currentConnection = null
            currentArgs = null
        }
    }

    private suspend fun waitConnected(timeoutMs: Long): IRemoteEngineService? =
        withTimeoutOrNull(timeoutMs) {
            _state.first { it is State.Connected || it is State.Error }
        }?.let { (it as? State.Connected)?.svc }
}