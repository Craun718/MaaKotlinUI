package com.maafw.naruto.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 小管家喵～
 * 负责 Shizuku 检查/授权/状态监听。
 * 适配参考：MAA-Meow 的 ShizukuManager（isPreV11 / isRunningAsRoot / 并发安全）。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 42

    private lateinit var appContext: Context
    private var initialized = false
    private val listeners = CopyOnWriteArraySet<ShizukuStateListener>()
    private val observingState = AtomicBoolean(false)

    interface ShizukuStateListener {
        fun onRequestPermissionResult(granted: Boolean)
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Log.i(TAG, "ShizukuManager 初始化完成，当前 binder 存活=${Shizuku.pingBinder()} 喵")
    }

    fun applicationContext(): Context = appContext

    /** Shizuku 是否可用（binder 存活）喵 */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "pingBinder 异常：${e.message}")
            false
        }
    }

    /** 是否已授权喵 */
    fun isReady(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** 老版本 Shizuku（v11 前）无需授权，直接可用喵 */
    fun isPreV11(): Boolean {
        return try {
            Shizuku.isPreV11()
        } catch (_: Exception) {
            false
        }
    }

    /** Shizuku 是否以 root 身份运行（uid 0，如 Root 授权启动的 Shizuku）喵 */
    fun isRunningAsRoot(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.getUid() == 0
        } catch (e: Exception) {
            Log.w(TAG, "getUid 失败：${e.message}")
            false
        }
    }

    /** Shizuku 应用是否已安装喵 */
    fun isAppInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 请求 Shizuku 权限喵
     * @param callback 是否成功授权（true=可用，false=不可用）
     */
    fun requestPermission(context: Context, callback: ((Boolean) -> Unit)? = null) {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku 没有运行，无法请求权限喵")
            callback?.invoke(false)
            return
        }
        if (isPreV11()) {
            Log.i(TAG, "Shizuku 为老版本（v11 前），无需授权喵")
            callback?.invoke(true)
            return
        }
        if (isReady()) {
            Log.i(TAG, "Shizuku 权限已经授予喵")
            callback?.invoke(true)
            return
        }
        // 临时注册一个一次性回调
        addListener(object : ShizukuStateListener {
            override fun onRequestPermissionResult(granted: Boolean) {
                callback?.invoke(granted)
                removeListener(this)
            }
        })
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    fun addListener(listener: ShizukuStateListener) {
        ensureStateObservation()
        listeners.add(listener)
    }

    fun removeListener(listener: ShizukuStateListener) {
        listeners.remove(listener)
    }

    private fun ensureStateObservation() {
        if (!observingState.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky {
            Log.i(TAG, "Shizuku Binder 已连接喵")
            notifyStateChanged()
        }
        Shizuku.addBinderDeadListener {
            Log.w(TAG, "Shizuku Binder 已断开喵")
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        // CopyOnWriteArraySet 天然并发安全，遍历中 removeListener 不会崩溃喵
        listeners.forEach { listener ->
            runCatching { listener.onRequestPermissionResult(isReady()) }
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku 权限请求结果 granted=$granted 喵")
            notifyStateChanged()
        }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku Binder 已连接喵")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku Binder 已断开喵")
    }
}