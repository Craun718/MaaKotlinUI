package com.maafw.naruto

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.model.OptionOverrideBuilder
import com.maafw.naruto.remote.RemoteEngineServiceImpl
import com.maafw.naruto.root.RootManager
import com.maafw.naruto.root.RootRemoteServiceConnector
import com.maafw.naruto.schedule.ScheduleHelper
import com.maafw.naruto.schedule.data.ScheduleStrategyRepository
import com.maafw.naruto.schedule.ui.ScheduleEditView
import com.maafw.naruto.schedule.ui.ScheduleListView
import com.maafw.naruto.shizuku.ShizukuManager
import com.maafw.naruto.ui.components.MaaBottomBar
import com.maafw.naruto.ui.components.MaaScreen
import com.maafw.naruto.ui.home.HomeScreen
import com.maafw.naruto.ui.onboarding.OnboardingScreen
import com.maafw.naruto.ui.schedule.ScheduleScreen
import com.maafw.naruto.ui.script.ScriptsScreen
import com.maafw.naruto.ui.settings.SettingsScreen
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面喵～
 * 现在引擎和虚拟屏都搬到 Shizuku UserService（shell 进程）里，
 * 应用侧只负责 UI、绑定远端服务、收发日志喵。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REMOTE_LOG_ACTION = "com.maafw.naruto.REMOTE_LOG"
    }

    // 使用 Compose State 才能触发界面重绘，否则远端引擎连上了 UI 还显示“未连接”喵
    private val remoteEngineState = mutableStateOf<IRemoteEngineService?>(null)
    private val remoteBoundState = mutableStateOf(false)
    private val remoteEngine: IRemoteEngineService? get() = remoteEngineState.value
    private val remoteBound: Boolean get() = remoteBoundState.value

    /**
     * 把应用私有外部目录传给远端 shell 进程，避免 shell 里 FakeContext 调 getExternalFilesDir 触发 UID 校验喵
     */
    private fun getUserDir(): String? = runCatching { getExternalFilesDir(null)?.absolutePath }.getOrNull()

    // 防止 bindUserService 调用期间重复进入
    private var remoteBinding = false
    private val remoteBindingLock = Any()

    private val remoteConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(remoteBindingLock) { remoteBinding = false }
            remoteEngineState.value = IRemoteEngineService.Stub.asInterface(service)
            remoteBoundState.value = true
            Log.i(TAG, "远端引擎服务已绑定喵")
            onEngineConnected()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(remoteBindingLock) { remoteBinding = false }
            runCatching { remoteEngineState.value?.unregisterStatusListener(engineStatusListener) }
            remoteEngineState.value = null
            remoteBoundState.value = false
            Log.i(TAG, "远端引擎服务已断开喵")
        }
    }

    /** 引擎绑定成功后的公共初始化（版本/分辨率/监听器）喵 */
    private fun onEngineConnected() {
        // 注册状态监听器（binder 回调：日志/运行状态/当前任务）喵
        runCatching { remoteEngine?.registerStatusListener(engineStatusListener) }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { remoteEngine?.setup(getUserDir()) }
                val version = withContext(Dispatchers.IO) { remoteEngine?.version() }
                version?.let { addLog("远端引擎: $it") }
                // 查询虚拟屏分辨率（预览 Surface 用）喵
                withContext(Dispatchers.IO) {
                    remoteEngine?.getDisplayResolution()?.takeIf { it.size >= 2 }?.let {
                        displayResolutionState.value = Pair(it[0], it[1])
                    }
                }
            }.onFailure { addLog("远端初始化失败: ${it.message}\n${it.stackTraceToString()}") }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder 已连接，尝试绑定远端引擎喵")
        bindRemoteEngine()
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 只接受 shell/root 进程（引擎）发送的广播，避免被伪造喵
            val sendingUid = android.os.Binder.getCallingUid()
            // 所有广播都打 logcat，便于排查 root 引擎 binder 广播是否到达（Android 16 上 ServiceManager 不可见）喵
            Log.d(TAG, "onReceive action=${intent?.action} sendingUid=$sendingUid")
            if (sendingUid != android.os.Process.SHELL_UID && sendingUid != 0) return
            when (intent?.action) {
                REMOTE_LOG_ACTION -> {
                    val log = intent.getStringExtra("log")
                    val running = intent.getBooleanExtra("running", false)
                    if (log != null) addLog(log)
                    updateRunningState(running)
                    // 事件驱动：引擎广播当前任务入口名喵
                    if (intent.hasExtra("current")) {
                        val cur = intent.getStringExtra("current").orEmpty()
                        if (cur.isNotBlank()) {
                            currentTaskState.value = cur
                        }
                    }
                }
                "com.maafw.naruto.TOUCH_EVENT" -> {
                    val action = intent.getIntExtra("action", -1)
                    val x = intent.getIntExtra("x", 0)
                    val y = intent.getIntExtra("y", 0)
                    if (action >= 0) {
                        touchEventState.value = intArrayOf(action, x, y)
                    }
                }
                RemoteEngineServiceImpl.ROOT_ENGINE_BINDER_ACTION -> {
                    // Root 模式：引擎进程直接把 binder 广播过来，绕开 ServiceManager.getService 的 hidden API 限制喵
                    // Intent.getIBinderExtra 在 SDK stub 里是 @hide，用反射读取（运行时真实类存在该方法）喵
                    val binder = runCatching {
                        Intent::class.java.getMethod("getIBinderExtra", String::class.java)
                            .invoke(intent, "binder") as? IBinder
                    }.getOrNull()
                    if (binder != null && binder.pingBinder()) {
                        addLog("收到 Root 引擎 binder 广播，连接中…")
                        connectRootEngine(binder)
                    } else {
                        addLog("Root 引擎 binder 广播无效，回退 ServiceManager 轮询")
                    }
                }
            }
        }
    }

    private val logBuffer = mutableStateListOf("等待运行日志…")
    private var runningCallback: ((Boolean) -> Unit)? = null
    private val virtualDisplayIdState = mutableIntStateOf(-1)
    // 虚拟屏分辨率（用于预览 Surface setFixedSize，修复黑屏）喵
    private val displayResolutionState = mutableStateOf(Pair(1280, 720))
    // 脚本触摸事件（供触摸预览显示脚本触摸位置，广播自远端引擎）喵
    private val touchEventState = mutableStateOf<IntArray?>(null)
    // 当前任务名（事件驱动，广播自引擎事件回调）喵
    private val currentTaskState = mutableStateOf("")

    // 引擎状态监听器（binder 回调，可靠事件驱动，不依赖广播）喵
    private val engineStatusListener = object : IEngineStatusListener.Stub() {
        override fun onStatusChanged(running: Boolean, currentEntry: String?) {
            updateRunningState(running)
            if (!currentEntry.isNullOrBlank()) {
                currentTaskState.value = currentEntry
            }
        }

        override fun onLog(message: String?) {
            if (!message.isNullOrBlank()) addLog(message)
        }
    }

    private fun addLog(message: String) {
        if (logBuffer.size >= 500) logBuffer.removeAt(0)
        logBuffer.add(message)
    }

    private fun updateRunningState(running: Boolean) {
        runningCallback?.invoke(running)
    }

    // ---- Root 模式辅助（设置里选 Root 时，系统操作直接用 su 执行，不依赖 Shizuku）喵 ----
    private fun runRootCommand(cmd: String) {
        Thread {
            runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(this)
        RootRemoteServiceConnector.initialize(this)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        scheduleExistingTasks()

        val interfaceData: MaaInterface? = AssetLoader.loadInterface(this)

        setContent {
            val baseDensity = LocalDensity.current
            val configuration = LocalConfiguration.current
            var currentScreen by remember { mutableStateOf(MaaScreen.HOME) }
            var running by remember { mutableStateOf(false) }
            val currentTask by currentTaskState
            // 主题（ themeMode 生效逻辑 + 莫奈动态取色）喵
            var theme by remember { mutableStateOf(SettingsRepository.getTheme(applicationContext)) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (theme) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> systemDark
            }
            val colorScheme = when (theme) {
                SettingsRepository.THEME_MONET -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (darkTheme) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                    } else if (darkTheme) darkColorScheme() else lightColorScheme()
                }
                SettingsRepository.THEME_DARK -> darkColorScheme()
                SettingsRepository.THEME_LIGHT -> lightColorScheme()
                else -> if (systemDark) darkColorScheme() else lightColorScheme()
            }
            // 页面缩放（ 字号缩放，用 Density 整体缩放含间距）喵
            var uiScale by remember { mutableStateOf(SettingsRepository.getUiScale(applicationContext)) }
            // 全屏预览（隐藏底部栏 + 系统栏）喵
            var isFullscreen by remember { mutableStateOf(false) }
            // 首次启动引导喵
            var showWelcome by remember { mutableStateOf(!SettingsRepository.isOnboarded(applicationContext)) }
            // 全局操作引导（聚光灯，Scaffold 之外渲染避免底栏遮挡/坐标错位）喵
            val guideController = remember { com.maafw.naruto.ui.components.GuideController() }
            // 定时任务编辑状态（ 式导航：列表 ↔ 编辑）
            var editingStrategyId by remember { mutableStateOf<String?>(null) }
            var inScheduleEdit by remember { mutableStateOf(false) }
            // 首次启动引导喵
            var showOnboarding by remember { mutableStateOf(!SettingsRepository.isOnboardingDone(applicationContext)) }

            val remoteEngine by remoteEngineState
            val remoteBound by remoteBoundState
            val remoteConnected = remoteEngine != null && remoteBound
            val virtualDisplayId by virtualDisplayIdState
            val displayResolution by displayResolutionState

            // 脚本触摸事件喵
            var scriptTouchMarkers by remember { mutableStateOf(listOf<IntArray>()) }
            val touchEvent by touchEventState
            LaunchedEffect(touchEvent) {
                val e = touchEvent ?: return@LaunchedEffect
                scriptTouchMarkers = (scriptTouchMarkers + e).takeLast(60)
                touchEventState.value = null
            }

            // 引擎运行状态与当前任务：事件驱动（引擎事件回调 → 广播 → 更新），无需轮询喵
            LaunchedEffect(Unit) {
                runningCallback = { running = it }
            }

            // 全屏预览时隐藏系统状态栏/导航栏（修复全屏仍有状态栏和底栏）喵
            LaunchedEffect(isFullscreen) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            val keepScreenOn = remember { SettingsRepository.isKeepScreenOn(applicationContext) }

            LaunchedEffect(running, keepScreenOn) {
                if (running && keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * uiScale,
                    fontScale = baseDensity.fontScale * uiScale,
                )
            ) {
            MaterialTheme(colorScheme = colorScheme) {
            // 首次启动引导（覆盖主界面）喵
            if (showOnboarding) {
                OnboardingScreen(onFinish = {
                    SettingsRepository.setOnboardingDone(applicationContext, true)
                    showOnboarding = false
                })
            } else {
            Scaffold(
                bottomBar = {
                    if (!isFullscreen) {
                        MaaBottomBar(current = currentScreen) { currentScreen = it }
                    }
                }
            ) { padding ->
                when (currentScreen) {
                    MaaScreen.HOME -> HomeScreen(
                        running = running,
                        currentTask = currentTask,
                        remoteConnected = remoteConnected,
                        displayId = virtualDisplayId,
                        displayResolution = displayResolution,
                        runMode = SettingsRepository.getRunMode(applicationContext),
                        onRequestShizuku = {
                            ShizukuManager.requestPermission(this@MainActivity) { granted ->
                                if (granted) bindRemoteEngine()
                            }
                        },
                        onOpenScripts = { currentScreen = MaaScreen.SCRIPT },
                        modifier = Modifier.padding(padding)
                    )
                    MaaScreen.SCRIPT -> ScriptsScreen(
                        running = running,
                        currentTask = currentTask,
                        interfaceData = interfaceData,
                        logs = logBuffer.toList(),
                        displayResolution = displayResolution,
                        isFullscreen = isFullscreen,
                        onFullscreenChange = { isFullscreen = it },
                        scriptTouchMarkers = scriptTouchMarkers,
                        onPreviewSurfaceAvailable = { surface ->
                            runCatching { remoteEngine?.setMonitorSurface(surface) }
                                .onFailure { addLog("预览 Surface 设置失败: ${it.message}") }
                        },
                        onStartProfile = { profileName ->
                            startEnabledTasks(interfaceData, profileName)
                        },
                        onStop = { stopRemoteEngine() },
                        onClearLogs = {
                            logBuffer.clear()
                        },
                        onScreenOff = {
                            // useHardwareScreenOff 开启 → 硬件熄屏；否则关虚拟屏（）喵
                            if (SettingsRepository.isUseHardwareScreenOff(applicationContext)) {
                                if (SettingsRepository.isRootMode(applicationContext)) {
                                    runRootCommand("input keyevent 26")
                                } else {
                                    runCatching { remoteEngine?.hardwareScreenOff() }
                                }
                                addLog("已执行硬件熄屏喵")
                            } else {
                                setDisplayPower(false)
                            }
                        },
                        onScreenOn = { setDisplayPower(true) },
                        onScreenshot = { captureScreenshot() },
                        onCloseGame = { closeGame() },
                        onToggleGameSound = { muted ->
                            if (SettingsRepository.isRootMode(applicationContext)) {
                                runRootCommand("media volume --stream 3 --set ${if (muted) 0 else 100}")
                                addLog(if (muted) "游戏已静音喵(root)" else "游戏已恢复声音喵(root)")
                            } else {
                                runCatching { remoteEngine?.setAudioMuted(muted) }
                                    .onSuccess { addLog(if (muted) "游戏已静音喵" else "游戏已恢复声音喵") }
                                    .onFailure { addLog("静音切换失败: ${it.message}") }
                            }
                        },
                        onInjectTouch = { action, x, y ->
                            // 引擎已连接（Shizuku 或 Root 引擎进程）时统一走引擎注入：
                            // 注入到虚拟屏 displayId，坐标正确，且支持 DOWN/MOVE/UP 完整手势（滑动/长按/拖动）喵
                            if (remoteEngine != null) {
                                runCatching { remoteEngine?.injectTouch(action, x, y) }
                                    .onFailure { addLog("触摸注入失败: ${it.message}") }
                            } else if (SettingsRepository.isRootMode(applicationContext)) {
                                // Root 引擎尚未连接时的兜底：su input tap（仅点击，主屏坐标，无法滑动）喵
                                if (action == android.view.MotionEvent.ACTION_DOWN) {
                                    runRootCommand("input tap $x $y")
                                }
                            }
                        },
                        guideController = guideController,
                        modifier = Modifier.padding(padding)
                    )
                    MaaScreen.SCHEDULE -> Box(modifier = Modifier.padding(padding)) {
                        if (inScheduleEdit) {
                            ScheduleEditView(
                                strategyId = editingStrategyId,
                                onBack = {
                                    inScheduleEdit = false
                                    editingStrategyId = null
                                }
                            )
                        } else {
                            ScheduleListView(
                                onEditStrategy = { id ->
                                    editingStrategyId = id
                                    inScheduleEdit = true
                                },
                                guideController = guideController
                            )
                        }
                    }
                    MaaScreen.SETTINGS -> Box(modifier = Modifier.padding(padding)) {
                        SettingsScreen(
                            logBuffer = logBuffer,
                            theme = theme,
                            onThemeChange = { theme = it },
                            uiScale = uiScale,
                            onUiScaleChange = { uiScale = it },
                            onResolutionChange = { res -> SettingsRepository.setResolution(applicationContext, res) },
                            onRunModeChange = { mode -> SettingsRepository.setRunMode(applicationContext, mode) },
                            onCaptureLogcat = {
                                runCatching { remoteEngine?.captureLogcat(5000) }.getOrNull()
                            }
                        )
                    }
                }
            }

            // 全局操作引导（覆盖全屏含底部导航栏，坐标对齐不偏移）喵
            if (guideController.isActive) {
                com.maafw.naruto.ui.components.SpotlightGuide(
                    steps = guideController.steps,
                    stepIndex = guideController.stepIndex,
                    targets = guideController.targets,
                    onNext = { guideController.next() },
                    onPrev = { guideController.prev() },
                    onSkip = { guideController.dismiss() }
                )
            }

            // 首次启动引导（权限说明 + 使用指引）喵
            if (showWelcome) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("欢迎使用 MAAFW 火影忍者") },
                    text = {
                        Column {
                            Text("本应用通过 Shizuku/Root 创建虚拟屏，自动运行火影忍者日常任务。", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("使用前请完成：", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("1. 安装并启动 Shizuku（或已 Root）", style = MaterialTheme.typography.bodySmall)
                            Text("2. 在设置页授权并选择运行模式", style = MaterialTheme.typography.bodySmall)
                            Text("3. 在脚本页配置任务后点击「开始任务」", style = MaterialTheme.typography.bodySmall)
                            Text("4. 在定时任务页可设置自动执行与后台唤醒", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("详情可随时在主页「权限检查」查看权限状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            SettingsRepository.setOnboarded(applicationContext)
                            showWelcome = false
                        }) { Text("开始使用") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            SettingsRepository.setOnboarded(applicationContext)
                            showWelcome = false
                        }) { Text("跳过") }
                    }
                )
            }
            }
        }
    }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(REMOTE_LOG_ACTION)
        filter.addAction("com.maafw.naruto.TOUCH_EVENT")
        filter.addAction(RemoteEngineServiceImpl.ROOT_ENGINE_BINDER_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 引擎在 shell/root 进程（不同 UID），必须 RECEIVER_EXPORTED 才能收到喵
                Context.RECEIVER_EXPORTED
            } else {
                0x00000002
            }
            registerReceiver(logReceiver, filter, null, null, flags)
        } else {
            registerReceiver(logReceiver, filter)
        }
        if (Shizuku.pingBinder()) bindRemoteEngine()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(logReceiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        if (SettingsRepository.isRootMode(applicationContext)) {
            RootRemoteServiceConnector.disconnect(remoteEngineState.value?.asBinder())
        } else {
            val args = remoteServiceArgs
            if (remoteBound && args != null) {
                try { Shizuku.unbindUserService(args, remoteConnection, true) } catch (_: Exception) {}
            }
        }
    }

    private var remoteServiceArgs: Shizuku.UserServiceArgs? = null

    private fun bindRemoteEngine() {
        // Root 模式：su + app_process 启动完整引擎（虚拟屏/触摸/引擎全部在 root 进程，与 Shizuku 同接口）喵
        if (SettingsRepository.isRootMode(applicationContext)) {
            synchronized(remoteBindingLock) {
                if (remoteBinding || remoteBound) return
                remoteBinding = true
            }
            if (!RootManager.isRootGranted()) {
                addLog("Root 未授权，正在请求…")
                if (!RootManager.requestRoot()) {
                    synchronized(remoteBindingLock) { remoteBinding = false }
                    addLog("Root 授权失败，请确认已 root 并允许授权喵")
                    return
                }
            }
            addLog("正在通过 Root 启动远端引擎…")
            RootRemoteServiceConnector.bind(
                onConnected = { binder ->
                    synchronized(remoteBindingLock) { remoteBinding = false }
                    if (remoteBound) return@bind
                    remoteEngineState.value = IRemoteEngineService.Stub.asInterface(binder)
                    remoteBoundState.value = true
                    addLog("Root 远端引擎已连接喵")
                    onEngineConnected()
                },
                onError = { e ->
                    synchronized(remoteBindingLock) { remoteBinding = false }
                    remoteBoundState.value = false
                    addLog("Root 引擎启动失败: ${e.message}")
                }
            )
            return
        }

        if (!Shizuku.pingBinder()) {
            addLog("Shizuku 未运行，无法绑定远端引擎喵")
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ShizukuManager.requestPermission(this) { granted ->
                if (granted) bindRemoteEngine()
            }
            return
        }
        synchronized(remoteBindingLock) {
            if (remoteBinding || remoteBound) return
            remoteBinding = true
        }

        val args = Shizuku.UserServiceArgs(ComponentName(this, RemoteEngineServiceImpl::class.java))
            .daemon(false)
            .processNameSuffix("remote_engine")
            .debuggable(true)
            .version(1)
        remoteServiceArgs = args

        try {
            Shizuku.bindUserService(args, remoteConnection)
            Log.i(TAG, "bindUserService called")
            addLog("正在绑定远端引擎…")
        } catch (e: Exception) {
            synchronized(remoteBindingLock) { remoteBinding = false }
            Log.e(TAG, "bindUserService failed", e)
            addLog("绑定远端引擎失败: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    /**
     * Root 模式：引擎进程广播过来的 binder 直接连接（绕开 ServiceManager.getService 的 hidden API 限制）喵
     */
    private fun connectRootEngine(binder: IBinder) {
        synchronized(remoteBindingLock) { remoteBinding = false }
        if (remoteBound) return
        remoteEngineState.value = IRemoteEngineService.Stub.asInterface(binder)
        remoteBoundState.value = true
        addLog("Root 远端引擎已连接喵")
        onEngineConnected()
    }

    private fun scheduleExistingTasks() {
        val items = com.maafw.naruto.data.schedule.ScheduleRepository.load(applicationContext)
        if (items.isNotEmpty()) {
            ScheduleHelper.rescheduleAll(applicationContext, items)
        }
        // 策略（：启动时重注册所有启用的定时策略）
        val strategies = ScheduleStrategyRepository(applicationContext).load()
        ScheduleHelper.rescheduleStrategies(applicationContext, strategies)
    }

    private fun startEnabledTasks(interfaceData: MaaInterface?, profileName: String = ProfileManager.DEFAULT_PROFILE_NAME) {
        val remote = remoteEngine
        if (remote == null) {
            addLog("远端引擎尚未连接，请授权 Shizuku 后重试喵")
            bindRemoteEngine()
            return
        }
        // 按当前选中的任务配置运行（不总是默认配置）喵
        val profile = if (profileName == ProfileManager.DEFAULT_PROFILE_NAME) {
            ProfileManager.loadDefault(applicationContext, interfaceData)
        } else {
            ProfileManager.load(applicationContext, profileName)
                ?: run {
                    addLog("配置 [$profileName] 不存在，回退到默认配置喵")
                    ProfileManager.loadDefault(applicationContext, interfaceData)
                }
        }
        addLog("正在运行配置：$profileName 喵")
        val enabled = profile.tasks.filter { it.enabled }
        if (enabled.isEmpty()) {
            addLog("默认配置里没有启用任何任务喵")
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
            addLog("启用的任务在资源里找不到喵")
            return
        }
        addLog("开始按顺序运行 ${items.length()} 个任务喵")

        runCatching {
            // 应用虚拟屏分辨率设置（720p/1080p）喵
            runCatching {
                val (w, h, dpi) = SettingsRepository.getResolutionFull(
                    SettingsRepository.getResolution(applicationContext), applicationContext
                )
                remote.setResolution(w, h, dpi)
                displayResolutionState.value = Pair(w, h)
                addLog("虚拟屏分辨率：${w}x${h}@${dpi}dpi喵")
            }
            // 启动任务时自动静音（ muteOnGameLaunch）喵
            if (SettingsRepository.isMuteOnGameLaunch(applicationContext)) {
                remote.setAudioMuted(true)
                addLog("已按设置静音游戏喵")
            }
            val displayId = remote.startVirtualDisplay()
            virtualDisplayIdState.value = displayId
            if (displayId < 0) {
                addLog("虚拟屏启动失败喵")
                // 适配提示（参考 MAA-Meow failVirtualDisplayStart）喵
                if (SettingsRepository.isRootMode(applicationContext)) {
                    addLog("当前为 Root 模式：请确认已授予 root 权限，或改用 Shizuku 模式")
                } else if (com.maafw.naruto.shizuku.ShizukuManager.isRunningAsRoot()) {
                    addLog("Shizuku 以 root 身份运行，虚拟屏创建可能受限；可在设置页切换 Root 模式重试")
                } else if (com.maafw.naruto.root.RootManager.isRootAvailable()) {
                    addLog("若 Shizuku 无法创建虚拟屏，可在设置页切换 Root 模式重试")
                } else {
                    addLog("请确认 Shizuku 为 adb/shell 模式并已授权")
                }
                return
            }
            addLog("虚拟屏已创建 displayId=$displayId")
            if (!remote.startTasksJson(items.toString())) {
                addLog("任务启动失败：查看上方引擎日志（如资源加载/控制器创建失败）或导出日志分析喵")
            }
        }.onFailure {
            addLog("启动远端引擎失败: ${it.message}")
            it.printStackTrace()
        }
    }

    private fun stopRemoteEngine() {
        runCatching {
            remoteEngine?.stopTask()
            remoteEngine?.stopVirtualDisplay()
        }
        virtualDisplayIdState.value = -1
        addLog("已停止远端引擎喵")
        updateRunningState(false)
    }

    private fun setDisplayPower(on: Boolean) {
        runCatching {
            remoteEngine?.setDisplayPower(on)
            addLog(if (on) "已点亮虚拟屏喵" else "已关闭虚拟屏喵")
        }.onFailure { addLog("关屏/亮屏失败: ${it.message}") }
    }

    private fun captureScreenshot() {
        runCatching {
            val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            val path = remoteEngine?.captureFramePng("$dir/screenshots")
            addLog(path?.let { "截图已保存: $it" } ?: "截图失败，无可用帧喵")
        }.onFailure { addLog("截图失败: ${it.message}") }
    }

    private fun closeGame() {
        runCatching {
            val ok = remoteEngine?.stopPackage("com.tencent.KiHan") ?: false
            addLog(if (ok) "已关闭游戏喵" else "关闭游戏失败喵")
        }
    }
}