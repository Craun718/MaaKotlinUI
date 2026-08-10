package com.maafw.naruto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maafw.naruto.IRemoteEngineService
import com.maafw.naruto.MainActivity
import com.maafw.naruto.R
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.model.OptionOverrideBuilder
import com.maafw.naruto.remote.RemoteEngineServiceImpl
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * 前台服务喵～
 * 用于定时任务等场景：绑定远端 Shizuku UserService 并执行默认 profile。
 */
class MaaEngineService : Service() {

    companion object {
        private const val TAG = "MaaEngineService"
        private const val NOTIFICATION_ID = 0x4D4141
        private const val CHANNEL_ID = "maa_engine_channel"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteService: IRemoteEngineService? = null
    @Volatile
    private var remoteBound = false
    // 后台唤醒：任务执行期间持有部分唤醒锁，锁屏/熄屏也不让 CPU 休眠喵
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "MaaFW:schedule"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 最多 30 分钟自动释放喵
            }
            Log.i(TAG, "已持有后台唤醒锁喵")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    /**
     * 把应用私有外部目录传给远端 shell 进程，避免 shell 里 FakeContext 调 getExternalFilesDir 触发 UID 校验喵
     */
    private fun getUserDir(): String? = runCatching { getExternalFilesDir(null)?.absolutePath }.getOrNull()

    private val remoteConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = IRemoteEngineService.Stub.asInterface(service)
            remoteBound = true
            Log.i(TAG, "远端引擎已绑定喵")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            remoteBound = false
            Log.i(TAG, "远端引擎已断开喵")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("MAAFW 定时任务待命中"))
        // 后台唤醒开启时持有唤醒锁（锁屏/熄屏也执行任务）喵
        if (com.maafw.naruto.data.settings.SettingsRepository.isScheduleWakeOn(applicationContext)) {
            acquireWakeLock()
        }
        if (intent?.getStringExtra("action") == "run_profile") {
            val profileName = intent.getStringExtra("profile_name") ?: "default"
            val forceStart = intent.getBooleanExtra("force_start", false)
            val autoSleep = intent.getBooleanExtra("auto_sleep", false)
            val closeGame = intent.getBooleanExtra("close_game", false)
            serviceScope.launch { runProfile(profileName, forceStart, autoSleep, closeGame) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        val args = remoteServiceArgs
        if (remoteBound && args != null) {
            runCatching { Shizuku.unbindUserService(args, remoteConnection, true) }
        }
        serviceScope.cancel()
    }

    private var remoteServiceArgs: Shizuku.UserServiceArgs? = null

    private suspend fun ensureRemoteConnected(): Boolean {
        if (remoteBound && remoteService != null) return true
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku 未运行，无法执行定时任务喵")
            return false
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku 未授权，无法执行定时任务喵")
            return false
        }
        val args = Shizuku.UserServiceArgs(ComponentName(this, RemoteEngineServiceImpl::class.java))
            .daemon(false)
            .processNameSuffix("remote_engine")
            .debuggable(true)
            .version(1)
        remoteServiceArgs = args
        Shizuku.bindUserService(args, remoteConnection)
        // 等待绑定完成，最多 10 秒喵
        var waited = 0
        while (!remoteBound && waited < 100) {
            delay(100)
            waited++
        }
        return remoteBound
    }

    private suspend fun runProfile(profileName: String, forceStart: Boolean, autoSleep: Boolean, closeGame: Boolean) {
        try {
            if (!ensureRemoteConnected()) {
                updateNotification("定时任务失败：Shizuku 未就绪")
                stopSelfDelayed()
                return
            }
            val remote = remoteService ?: run {
                updateNotification("定时任务失败：远端引擎未连接")
                stopSelfDelayed()
                return
            }

            remote.setup(getUserDir())

            // 启动任务时自动静音（ muteOnGameLaunch）喵
            if (SettingsRepository.isMuteOnGameLaunch(applicationContext)) {
                runCatching { remote.setAudioMuted(true) }
            }

            // forceStart：先停止已有任务（ 强制开始语义）
            if (forceStart && remote.isRunning) {
                remote.stopTask()
            }

            val displayId = remote.startVirtualDisplay()
            if (displayId < 0) {
                updateNotification("定时任务失败：虚拟屏创建失败")
                stopSelfDelayed()
                return
            }

            val interfaceData = AssetLoader.loadInterface(applicationContext)
            val profile = if (profileName == "default") {
                ProfileManager.loadDefault(applicationContext, interfaceData)
            } else {
                ProfileManager.load(applicationContext, profileName) ?: run {
                    updateNotification("定时任务：配置 [$profileName] 不存在")
                    remote.stopVirtualDisplay()
                    stopSelfDelayed()
                    return
                }
            }
            val enabled = profile.tasks.filter { it.enabled }
            if (enabled.isEmpty()) {
                updateNotification("定时任务：配置 [$profileName] 无启用任务")
                remote.stopVirtualDisplay()
                stopSelfDelayed()
                return
            }

            val tasks = interfaceData?.task ?: emptyList()
            val items = JSONArray()
            for (entry in enabled.map { it.entry }) {
                val task = tasks.find { it.entry == entry } ?: continue
                val config = SettingsRepository.getTaskConfig(applicationContext, entry)
                val override = OptionOverrideBuilder.build(task, config.options, interfaceData)
                items.put(JSONObject().apply {
                    put("entry", entry)
                    put("options", JSONObject().apply {
                        config.options.forEach { (k, v) -> put(k, v) }
                    })
                    override?.let { put("pipeline_override", it) }
                })
            }
            if (items.length() == 0) {
                updateNotification("定时任务：无有效任务")
                remote.stopVirtualDisplay()
                stopSelfDelayed()
                return
            }

            updateNotification("正在执行配置 [$profileName]（${items.length()} 个任务）")
            if (!remote.startTasksJson(items.toString())) {
                updateNotification("定时任务：启动失败")
                remote.stopVirtualDisplay()
                stopSelfDelayed()
                return
            }

            // 等待任务结束喵
            while (remote.isRunning) {
                delay(1000)
            }

            // autoSleep：任务结束后自动熄屏（）
            if (autoSleep) {
                remote.setDisplayPower(false)
            }
            remote.stopVirtualDisplay()

            // closeGame：任务结束后关闭游戏（）
            if (closeGame || SettingsRepository.isCloseGameAfterTask(applicationContext)) {
                runCatching { remote.stopPackage("com.tencent.KiHan") }
            }

            updateNotification("定时任务执行完毕")
            sendCompletionNotification("MAAFW 定时任务", "任务执行完毕喵")
            // 第三方通知推送（喵提醒/Server酱/钉钉/SMTP/Webhook）喵
            runCatching { com.maafw.naruto.data.notify.NotificationPusher.push(applicationContext, "MAAFW 定时任务完成", "任务执行完毕喵", true) }
        } catch (e: Exception) {
            Log.e(TAG, "runProfile error: ${e.message}", e)
            updateNotification("定时任务异常：${e.message}")
            sendCompletionNotification("MAAFW 定时任务", "任务异常：${e.message}")
            runCatching { com.maafw.naruto.data.notify.NotificationPusher.push(applicationContext, "MAAFW 定时任务出错", "任务异常：${e.message}", false) }
        } finally {
            stopSelfDelayed()
        }
    }

    private fun stopSelfDelayed() {
        serviceScope.launch {
            delay(3000)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAAFW 引擎",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "定时任务和脚本运行通知喵" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAAFW 火影忍者")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** 任务完成/失败通知（按设置控制声音/振动， 通知系统）喵 */
    private fun sendCompletionNotification(title: String, text: String) {
        if (!SettingsRepository.isNotificationEnabled(applicationContext)) return
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        var defaults = 0
        if (SettingsRepository.isNotificationSound(applicationContext)) {
            defaults = defaults or NotificationCompat.DEFAULT_SOUND
        }
        if (SettingsRepository.isNotificationVibrate(applicationContext)) {
            defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
        }
        if (defaults != 0) builder.setDefaults(defaults)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, builder.build())
    }
}