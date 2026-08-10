package com.maafw.naruto.remote

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.maafw.naruto.IEngineStatusListener
import com.maafw.naruto.IRemoteEngineService
import com.maafw.naruto.bridge.NativeBridge
import com.maafw.naruto.bridge.NativeBridgeLib
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.maa.AssetResourceDeployer
import com.maafw.naruto.maa.CustomActions
import com.maafw.naruto.maa.MaaFrameworkEngine
import com.maafw.naruto.remote.internal.ActivityUtils
import com.maafw.naruto.remote.internal.VirtualDisplayManager
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.Workarounds
import com.maafw.naruto.third.wrappers.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku UserService 实现喵。
 * 运行在 shell 进程，负责创建虚拟屏、驱动 MaaFramework。
 */
class RemoteEngineServiceImpl(private val context: Context) : IRemoteEngineService.Stub() {

    companion object {
        private const val TAG = "RemoteEngineService"

        /** Root 模式：引擎进程把 binder 通过广播直传给 App 的 action 喵 */
        const val ROOT_ENGINE_BINDER_ACTION = "com.maafw.naruto.ROOT_ENGINE_BINDER"
    }

    init {
        Ln.i("$TAG init, pid=${Process.myPid()}, uid=${Process.myUid()}")
        if (Process.myUid() != android.os.Process.SHELL_UID) {
            Ln.e("$TAG 当前 UID=${Process.myUid()} 不是 shell(2000)，" +
                    "请确认 Shizuku 使用 adb/shell 模式启动，而非 root 模式喵")
        }
        Workarounds.apply()
        // 确保 JNA 在加载 MaaFramework 前知道 so 目录
        System.setProperty("jna.library.path", context.applicationInfo.nativeLibraryDir)
        // JNA 解包临时 so 时不能走 app 私有目录，否则 shell UID 会触发 UID/PKG 校验
        System.setProperty("jna.tmpdir", "/data/local/tmp")
        // 触发 NativeBridgeLib 加载 libbridge.so
        val ping = NativeBridgeLib.ping()
        Ln.i("$TAG NativeBridgeLib ping=$ping")

        // Root 模式：把自身 binder 通过显式广播直达 manifest 静态 receiver（RootBinderReceiver）。
        // Android 16 限制：uid0 进程发的广播会被系统丢弃、app 进程 getService 也拿不到服务。
        // 解法：广播瞬间临时降权到 App 的 uid（sendingUid 与 App 一致即可收到），发完立即提权回 root 喵。
        try {
            val intent = Intent(ROOT_ENGINE_BINDER_ACTION).apply {
                component = android.content.ComponentName(
                    context.packageName,
                    "com.maafw.naruto.root.RootBinderReceiver"
                )
            }
            // Intent.putExtra(String, IBinder) 在 SDK stub 里是 @hide，用反射写入（运行时真实类存在该方法）喵
            runCatching {
                Intent::class.java.getMethod("putExtra", String::class.java, IBinder::class.java)
                    .invoke(intent, "binder", this@RemoteEngineServiceImpl)
            }.onFailure { Ln.w("$TAG 反射 putExtra binder 失败: ${it.message}") }

            val appUid = context.applicationInfo.uid
            // 降权到 App uid（real+effective=appUid，saved 保持 0），广播身份与 App 一致才能被收到喵
            val drop = setProcessUid(appUid)
            if (!drop) Ln.w("$TAG 降权到 uid=$appUid 失败，仍以 root 身份发广播（可能被系统丢弃）")
            try {
                context.sendBroadcast(intent)
            } finally {
                val raise = setProcessUid(0) // 提权回 root
                if (!raise) Ln.e("$TAG 提权回 root 失败（后续引擎功能可能异常）")
            }
            Ln.i("$TAG 已降权广播 root 引擎 binder 给 App（uid=$appUid）喵")
        } catch (e: Exception) {
            Ln.e("$TAG 广播 root 引擎 binder 失败: ${e.message}")
        }
    }

    /**
     * 切换进程 uid（保留 saved uid=0 以便提权）喵。
     * 用公开 API android.system.Os.setresuid(uid, uid, 0)：real+effective=uid、saved 恒为 0，之后可 setresuid(0,0,0) 提权回 root。
     * 注意不能用 setreuid——POSIX 下 setreuid(10750,10750) 会把 saved uid 也置为 10750，导致无法提权回 root 喵。
     */
    private fun setProcessUid(uid: Int): Boolean {
        return runCatching {
            // android.system.Os.setresuid 在 SDK stub 里是 @hide，用反射调用（运行时真实类存在；引擎进程有全豁免）喵
            val m = android.system.Os::class.java.getMethod(
                "setresuid",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            m.invoke(null, uid, uid, 0)
            true
        }.getOrDefault(false)
    }

    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: MaaFrameworkEngine? = null
    private var engineJob: kotlinx.coroutines.Job? = null
    private var running = false
    @Volatile
    private var destroyed = false
    private var userDir: String? = null
    /** 当前手势 DOWN 的 downTime（DOWN/MOVE/UP 共享，保证滑动/长按正常）喵 */
    private var currentDownTime = 0L

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        Ln.i("$TAG destroy()")
        stopAll()
        remoteScope.cancel()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun version(): String {
        return runCatching {
            "NativeBridge ping=${NativeBridgeLib.ping()}, MaaFW=${engine?.version ?: "待机（任务启动后加载）"}"
        }.getOrDefault("unknown")
    }

    override fun setup(userDir: String?): Boolean {
        Ln.i("$TAG setup($userDir)")
        this.userDir = userDir
        sendLog("引擎 setup：userDir=$userDir")
        return try {
            // 资源部署到 App 外部目录（userDir），shell uid 可写（参考 MAA-Meow 方案）喵
            val base = AssetResourceDeployer.deploy(context, userDir)
            sendLog("资源部署完成：$base")
            true
        } catch (e: Exception) {
            Ln.e("$TAG setup failed: ${e.message}")
            e.printStackTrace()
            sendLog("资源部署失败：${e.message}\n${e.stackTraceToString()}")
            false
        }
    }

    override fun startVirtualDisplay(): Int {
        Ln.i("$TAG startVirtualDisplay()")
        return try {
            val id = VirtualDisplayManager.start()
            if (id >= 0) {
                NativeBridge.setDisplayId(id)
                sendLog("虚拟屏 displayId=$id，已告知 NativeBridge")
            } else {
                sendLog("虚拟屏创建失败：返回 displayId=$id")
            }
            id
        } catch (e: Throwable) {
            val root = unwrapThrowable(e)
            Ln.e("$TAG startVirtualDisplay failed: ${root.javaClass.simpleName}: ${root.message}")
            root.printStackTrace()
            val reason = when (root) {
                is SecurityException -> "权限被拒绝，请确认 Shizuku 为 adb/shell 模式喵"
                is IllegalArgumentException -> "参数非法：${root.message}"
                is IllegalStateException -> "状态异常：${root.message}"
                else -> "${root.javaClass.simpleName}: ${root.message}"
            }
            sendLog("虚拟屏启动异常：$reason")
            -1
        }
    }

    private fun unwrapThrowable(t: Throwable): Throwable {
        var cause = t
        while ((cause is java.lang.reflect.InvocationTargetException || cause is RuntimeException) && cause.cause != null) {
            cause = cause.cause!!
        }
        return cause
    }

    override fun stopVirtualDisplay() {
        Ln.i("$TAG stopVirtualDisplay()")
        VirtualDisplayManager.stop()
    }

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG setMonitorSurface(${surface != null})")
        VirtualDisplayManager.setMonitorSurface(surface)
        NativeBridgeLib.setPreviewSurface(surface)
        // 绑定预览 Surface 时再次确保虚拟屏处于点亮状态，否则截图/预览黑屏喵
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId >= 0) {
            runCatching {
                ServiceManager.getDisplayManager().requestDisplayPower(displayId, true)
            }.onFailure { Ln.w("$TAG setMonitorSurface: requestDisplayPower failed: ${it.message}") }
        }
    }

    override fun startTask(entry: String?, pipelineOverride: String?): Boolean {
        if (entry == null) return false
        val config = SettingsRepository.getTaskConfig(context, entry)
        val obj = JSONObject().apply {
            put("entry", entry)
            put("options", JSONObject(config.options.toMap()))
            pipelineOverride?.let { put("pipeline_override", it) }
        }
        return startTasksJson(JSONArray().apply { put(obj) }.toString())
    }

    override fun startTasksJson(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val tasks = runCatching { JSONArray(json) }.getOrNull() ?: return false
        if (tasks.length() == 0) return false
        if (running) {
            Ln.w("$TAG engine already running")
            return false
        }
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId < 0) {
            Ln.e("$TAG virtual display not ready")
            return false
        }

        val cfg = VirtualDisplayManager.getConfig()
        val libDir = context.applicationInfo.nativeLibraryDir
        val bridgePath = "$libDir/libbridge.so"
        val resourceBase = runCatching {
            AssetResourceDeployer.deploy(context, userDir)
        }.getOrElse { null } ?: run {
            Ln.e("$TAG resource deploy failed")
            return false
        }

        running = true
        engineJob = remoteScope.launch {
            try {
                // 虚拟屏刚创建时是空屏（OWN_CONTENT_ONLY，无任何内容），SurfaceFlinger 不产生帧，
                // MaaFramework 管线 screencap 拿不到图像会卡死在 start_up（StartApp 永不执行）。
                // 所以先把游戏预启动到虚拟屏上，让画面先流动起来，之后 start_up 的 StartApp
                // 才能正常执行（此时游戏已在虚拟屏，只会被带到前台）喵。
                runCatching {
                    val pkg = runCatching {
                        val f = File(resourceBase, "pipeline/Startup.json")
                        if (f.exists()) {
                            JSONObject(f.readText())
                                .optJSONObject("start_up")
                                ?.optString("package")
                                .orEmpty()
                        } else ""
                    }.getOrDefault("").ifBlank { "com.tencent.KiHan" }
                    sendLog("预启动游戏 $pkg 到虚拟屏 displayId=$displayId 喵")
                    var ok = ActivityUtils.startApp(pkg, displayId, forceStop = false)
                    if (!ok) {
                        // AM API 启动失败时用 am start --display 兜底（真机验证可用）
                        Ln.w("$TAG preLaunch: startApp failed, fallback to am start --display")
                        ok = ActivityUtils.startAppViaAmCommand(pkg, displayId)
                    }
                    if (ok) {
                        ActivityUtils.ensureAppOnDisplay(pkg, displayId)
                        val baseline = NativeBridgeLib.getFrameCount()
                        val deadline = SystemClock.uptimeMillis() + 8_000
                        while (SystemClock.uptimeMillis() < deadline) {
                            if (NativeBridgeLib.getFrameCount() > baseline) {
                                sendLog("游戏画面已开始流动，继续启动任务喵")
                                break
                            }
                            SystemClock.sleep(100)
                        }
                    } else {
                        sendLog("预启动失败（游戏可能未安装），继续尝试运行任务喵")
                    }
                }.onFailure { Ln.e("$TAG preLaunch failed: ${it.message}") }

                sendLog("正在初始化 MaaFramework 引擎喵")
                // 日志目录必须由 App 进程传过来，shell 进程里 FakeContext.getExternalFilesDir 会触发 UID/PKG 校验；
                // shell 模式可能写不了 App 外部目录，失败时回退 /data/local/tmp 喵
                val logDir = userDir?.let { File(it, "maa_logs") }
                    ?.takeIf { runCatching { it.mkdirs() }.getOrDefault(false) && it.canWrite() }
                    ?: File("/data/local/tmp", "maa_logs_${context.packageName}").also { it.mkdirs() }
                // custom action 的清理类动作需要文件目录喵
                CustomActions.setFilesDir(userDir?.let { File(it, "maa_files") })
                CustomActions.clearCounters()
                val maa = MaaFrameworkEngine(context).apply { init(logDir) }
                engine = maa
                Ln.i("$TAG MaaFramework version ${maa.version}")
                sendLog("MaaFramework 版本 ${maa.version}")
                if (!maa.loadResource(resourceBase)) {
                    Ln.e("$TAG loadResource failed")
                    sendLog("资源加载失败：$resourceBase（pipeline/image/model 缺失或 MaaFramework 不支持）喵")
                    running = false
                    return@launch
                }
                sendLog("资源加载完成喵")
                if (!maa.createController(bridgePath, cfg.width, cfg.height, displayId)) {
                    Ln.e("$TAG createController failed")
                    sendLog("控制器创建失败：libbridge=$bridgePath 分辨率=${cfg.width}x${cfg.height} displayId=$displayId（请确认 Shizuku/Root 权限）喵")
                    running = false
                    return@launch
                }
                sendLog("控制器创建成功喵")
                if (!maa.createTasker()) {
                    Ln.e("$TAG createTasker failed")
                    sendLog("任务器创建失败喵（引擎初始化异常）")
                    running = false
                    return@launch
                }
                sendLog("任务器创建成功喵")
                sendLog("MaaFramework 版本：${maa.version}")
                // 注册任务事件回调（事件驱动：任务开始/完成/失败时广播，无需轮询）喵
                registerEventSink(maa)

                for (i in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(i)
                    val entry = task.optString("entry", "")
                    val options = task.optJSONObject("options")?.let { obj ->
                        val map = mutableMapOf<String, String>()
                        obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
                        map
                    } ?: emptyMap()
                    val override = task.optString("pipeline_override", "").takeIf { it.isNotBlank() }
                    Ln.i("$TAG start task $entry options=$options")
                    sendLog("开始任务 $entry")
                    if (!maa.startTask(entry, override)) {
                        Ln.e("$TAG startTask $entry failed")
                        sendLog("任务 $entry 启动失败喵")
                        break
                    }
                    val status = maa.waitTask()
                    Ln.i("$TAG task $entry finished status=$status")
                    sendLog("任务 $entry 结束，状态 $status")
                    if (status != 3000) break
                }
                sendLog("全部任务执行完毕喵")
            } catch (e: Exception) {
                Ln.e("$TAG engine error: ${e.message}")
                e.printStackTrace()
                // 输出完整堆栈，便于定位问题喵
                sendLog("引擎异常：${e.message}\n${e.stackTraceToString()}")
            } finally {
                engine?.destroy()
                engine = null
                running = false
            }
        }
        return true
    }

    override fun stopTask() {
        Ln.i("$TAG stopTask()")
        stopAll()
    }

    override fun isRunning(): Boolean = running

    override fun currentTask(): String = engine?.let { "" } ?: ""

    override fun startActivity(intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun startActivityOnDisplay(intent: Intent?, displayId: Int): Boolean {
        if (intent == null) return false
        return ActivityUtils.startActivity(intent, displayId)
    }

    override fun isPackageInstalled(packageName: String?): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName ?: return false, 0)
            true
        }.getOrDefault(false)
    }

    override fun moveAppToVirtualDisplay(packageName: String?): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId < 0 || packageName.isNullOrBlank()) return false
        return ActivityUtils.moveAppTaskToDisplay(packageName, displayId)
    }

    override fun setDisplayPower(on: Boolean) {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId < 0) return
        runCatching {
            ServiceManager.getDisplayManager().requestDisplayPower(displayId, on)
            Ln.i("$TAG setDisplayPower($on) displayId=$displayId")
        }.onFailure {
            Ln.e("$TAG setDisplayPower failed: ${it.message}")
        }
    }

    override fun captureFramePng(dirPath: String?): String? {
        if (dirPath.isNullOrBlank()) return null
        val bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: run {
            Ln.w("$TAG captureFramePng: no frame available")
            return null
        }
        return try {
            val dir = java.io.File(dirPath).apply { mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "screenshot_$timestamp.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            Ln.i("$TAG captureFramePng saved ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Ln.e("$TAG captureFramePng error: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    override fun stopPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            Runtime.getRuntime().exec("am force-stop $packageName").waitFor()
            true
        }.getOrDefault(false)
    }

    override fun setAudioMuted(muted: Boolean): Boolean {
        return runCatching {
            // 1) 优先 AudioManager.setStreamMute（shell 有 MODIFY_AUDIO_SETTINGS）喵
            var ok = runCatching {
                val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        if (muted) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
                } else {
                    @Suppress("DEPRECATION")
                    am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, muted)
                }
                true
            }.getOrDefault(false)
            // 2) fallback：cmd audio（API 30+）
            if (!ok) {
                ok = runCatching {
                    val cmd = "cmd audio set-stream-mute ${android.media.AudioManager.STREAM_MUSIC} ${if (muted) 1 else 0}"
                    Runtime.getRuntime().exec(cmd).waitFor() == 0
                }.getOrDefault(false)
            }
            // 3) fallback：su
            if (!ok) {
                ok = runCatching {
                    val cmd = "media volume --stream ${android.media.AudioManager.STREAM_MUSIC} --set ${if (muted) 0 else 100}"
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
                }.getOrDefault(false)
            }
            Ln.i("$TAG setAudioMuted($muted) ok=$ok")
            ok
        }.getOrDefault(false)
    }

    override fun injectTouch(action: Int, x: Int, y: Int): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId < 0) return false
        val ok = runCatching {
            // 同一手势的 DOWN/MOVE/UP 必须共享同一 downTime，否则滑动/长按会被系统识别为多次点击喵
            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> currentDownTime = SystemClock.uptimeMillis()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> currentDownTime = 0L
            }
            val eventTime = SystemClock.uptimeMillis()
            val down = if (currentDownTime != 0L) currentDownTime else eventTime
            val event = android.view.MotionEvent.obtain(
                down, eventTime, action, x.toFloat(), y.toFloat(),
                1.0f, 1.0f, 0, 1f, 1f, 0, 0
            )
            try {
                com.maafw.naruto.third.wrappers.InputManager.setDisplayId(event, displayId)
                ServiceManager.getInputManager().injectInputEvent(
                    event,
                    com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
                )
            } finally {
                event.recycle()
            }
        }.getOrDefault(false)
        if (ok) {
            // 广播触摸事件，供触摸预览显示脚本触摸位置喵
            sendTouchEvent(action, x, y)
        }
        return ok
    }

        override fun getDisplayResolution(): IntArray {
        val cfg = VirtualDisplayManager.getConfig()
        return intArrayOf(cfg.width, cfg.height)
    }

    /** 抓取 logcat（shell 进程有 READ_LOGS，可读全量含引擎自身日志）喵 */
    override fun captureLogcat(lines: Int): String? {
        return runCatching {
            val n = if (lines > 0) lines else 3000
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", n.toString(), "-v", "time"))
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text
        }.getOrNull()
    }

    override fun hardwareScreenOff() {
        var ok = runCatching {
            // 1) 反射 PowerManager.goToSleep（shell 有 DEVICE_POWER）喵
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val m = pm.javaClass.getMethod("goToSleep", Long::class.java, Int::class.java, Int::class.java)
            m.invoke(pm, SystemClock.uptimeMillis() + 1, 0, 0)
            true
        }.getOrDefault(false)
        if (!ok) {
            // 2) input keyevent 26
            ok = runCatching { Runtime.getRuntime().exec("input keyevent 26").waitFor() == 0 }.getOrDefault(false)
        }
        if (!ok) {
            // 3) su
            ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26")).waitFor() == 0 }.getOrDefault(false)
        }
        Ln.i("$TAG hardwareScreenOff ok=$ok")
    }

    override fun setResolution(width: Int, height: Int, dpi: Int): Boolean {
        return runCatching {
            VirtualDisplayManager.setResolution(width, height, dpi)
            Ln.i("$TAG setResolution($width x $height @ $dpi)")
            true
        }.getOrDefault(false)
    }

    private fun stopAll() {
        engineJob?.cancel()
        engineJob = null
        runBlocking { kotlin.runCatching { engine?.destroy() } }
        engine = null
        running = false
    }

    private fun sendLog(message: String) {
        // 1) binder 回调（可靠，同通道）喵
        notifyLog(message)
        // 2) 广播兜底（兼容旧逻辑）
        try {
            val intent = Intent("com.maafw.naruto.REMOTE_LOG").apply {
                setPackage(com.maafw.naruto.BuildConfig.APPLICATION_ID)
                putExtra("log", message)
                putExtra("running", running)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.e("sendLog failed: ${e.message}")
        }
    }

    /** 广播脚本触摸事件（供触摸预览显示脚本触摸位置）喵 */
    private fun sendTouchEvent(action: Int, x: Int, y: Int) {
        try {
            val intent = Intent("com.maafw.naruto.TOUCH_EVENT").apply {
                setPackage(com.maafw.naruto.BuildConfig.APPLICATION_ID)
                putExtra("action", action)
                putExtra("x", x)
                putExtra("y", y)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.e("sendTouchEvent failed: ${e.message}")
        }
    }

    /** 任务事件回调（事件驱动，任务开始/完成/失败时广播状态，供 UI 同步）喵 */
    private fun registerEventSink(maa: MaaFrameworkEngine) {
        val sink = object : com.maafw.naruto.maa.MaaEventCallback {
            override fun invoke(handle: com.sun.jna.Pointer?, message: String?, detailsJson: String?, transArg: com.sun.jna.Pointer?) {
                try {
                    when (message) {
                        "Tasker.Task.Starting" -> {
                            val entry = runCatching { org.json.JSONObject(detailsJson ?: "{}").optString("entry", "") }
                                .getOrDefault("")
                            sendLog(if (entry.isNotBlank()) "任务开始：$entry" else "任务开始")
                            sendRunningState(true, entry)
                        }
                        "Tasker.Task.Succeeded" -> {
                            sendLog("任务完成")
                            sendRunningState(false, null)
                        }
                        "Tasker.Task.Failed" -> {
                            sendLog("任务失败")
                            sendRunningState(false, null)
                        }
                    }
                } catch (e: Exception) {
                    Ln.w("eventSink error: ${e.message}")
                }
            }
        }
        maa.addSink(sink)
    }

    /** 广播运行状态 + 当前任务（binder 回调 + 广播兜底，事件驱动不轮询）喵 */
    private fun sendRunningState(running: Boolean, currentEntry: String?) {
        notifyStatus(running, currentEntry)
        try {
            val intent = Intent("com.maafw.naruto.REMOTE_LOG").apply {
                setPackage(com.maafw.naruto.BuildConfig.APPLICATION_ID)
                putExtra("running", running)
                putExtra("current", currentEntry ?: "")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.e("sendRunningState failed: ${e.message}")
        }
    }

    // ==================== binder 状态回调（可靠事件驱动，不依赖广播） ====================

    private val statusListeners = android.os.RemoteCallbackList<IEngineStatusListener>()

    override fun registerStatusListener(listener: IEngineStatusListener?) {
        if (listener != null) statusListeners.register(listener)
    }

    override fun unregisterStatusListener(listener: IEngineStatusListener?) {
        if (listener != null) statusListeners.unregister(listener)
    }

    /** 回调日志给已注册的 listener 喵 */
    private fun notifyLog(message: String) {
        val n = statusListeners.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { statusListeners.getBroadcastItem(i).onLog(message) }
            }
        } finally {
            statusListeners.finishBroadcast()
        }
    }

    /** 回调运行状态 + 当前任务给 listener 喵 */
    private fun notifyStatus(running: Boolean, currentEntry: String?) {
        val n = statusListeners.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { statusListeners.getBroadcastItem(i).onStatusChanged(running, currentEntry) }
            }
        } finally {
            statusListeners.finishBroadcast()
        }
    }
}