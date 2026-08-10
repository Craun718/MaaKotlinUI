@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maafw.naruto.ui.script

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.components.GuideStep
import com.maafw.naruto.ui.components.SpotlightGuide
import com.maafw.naruto.ui.components.rememberGuideTarget
import com.maafw.naruto.ui.theme.MaaDesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 脚本页喵～
 * 完整 BackgroundTaskView.kt：
 * - VirtualDisplayPreview：16:9 预览卡片 + 未运行/等待Surface 覆盖层 + 状态指示点
 * - 预览 Surface：RGBA_8888 + setFixedSize(虚拟屏分辨率) + 尺寸匹配后才发送（修复黑屏 bug）
 * - 全屏预览：点击预览进入全屏，支持触摸注入（复制  坐标换算）
 * - BackgroundMoreActionsOverlay：关屏 / 关游戏 / 静音切换 / 截图 + 自动设置（启动静音等）
 * - 任务勾选改为不可变替换，立即刷新（修复勾选不刷新 bug）
 */
@Composable
fun ScriptsScreen(
    running: Boolean,
    currentTask: String,
    interfaceData: MaaInterface?,
    logs: List<String>,
    displayResolution: Pair<Int, Int>,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    scriptTouchMarkers: List<IntArray>,
    onPreviewSurfaceAvailable: (Surface?) -> Unit,
    onStartProfile: (String) -> Unit,
    onStop: () -> Unit,
    onClearLogs: () -> Unit,
    onScreenOff: () -> Unit,
    onScreenOn: () -> Unit,
    onScreenshot: () -> Unit,
    onCloseGame: () -> Unit,
    onToggleGameSound: (Boolean) -> Unit,
    onInjectTouch: (action: Int, x: Int, y: Int) -> Unit,
    guideController: com.maafw.naruto.ui.components.GuideController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val allTasks = remember {
        interfaceData?.task?.takeIf { it.isNotEmpty() }
            ?: listOf(MaaTask(name = "进入火影", entry = "start_up"))
    }
    var profile by remember { mutableStateOf(ProfileManager.loadDefault(context, interfaceData)) }
    // 记住上次选择的配置（持久化），切换后下次打开沿用喵
    var currentProfileName by remember {
        mutableStateOf(SettingsRepository.getCurrentProfile(context))
    }
    var profiles by remember { mutableStateOf(ProfileManager.listProfiles(context).ifEmpty { listOf(ProfileManager.DEFAULT_PROFILE_NAME) }) }
    var isProfileMode by remember { mutableStateOf(false) }
    var selectedTaskEntry by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var isAddingTask by remember { mutableStateOf(false) }

    // 加载当前配置的任务列表（非默认配置）喵
    LaunchedEffect(currentProfileName) {
        if (currentProfileName != ProfileManager.DEFAULT_PROFILE_NAME) {
            profile = ProfileManager.load(context, currentProfileName)
                ?: ProfileManager.Profile(currentProfileName, mutableListOf()).also { ProfileManager.save(context, it) }
        } else {
            profile = ProfileManager.loadDefault(context, interfaceData)
        }
    }

    // 配置列表实时刷新（新建/删除/重命名后调用）喵
    fun refreshProfiles() {
        profiles = ProfileManager.listProfiles(context).ifEmpty { listOf(ProfileManager.DEFAULT_PROFILE_NAME) }
    }

    // ---- 首次操作引导（聚光灯，全局渲染避免底栏遮挡/偏移）喵 ----
    val guideTargets = guideController.targets
    val guideSteps = remember {
        listOf(
            GuideStep("preview", "虚拟屏预览", "这里是虚拟屏实时画面。点击可进入全屏，全屏后可点击操作游戏。"),
            GuideStep("panel", "任务列表与配置", "左侧是任务列表：顶部按钮可切换「任务配置」与「编辑任务」；右侧是任务参数设置。"),
            GuideStep("bottom", "开始 / 停止", "配置好任务后，点击「开始任务」运行；运行中可随时「停止」。右上角 ⋮ 有更多操作（静音/截图/关屏等）。")
        )
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("maa_guide", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("scripts_guided", false)) {
            guideController.onFinished = {
                context.getSharedPreferences("maa_guide", Context.MODE_PRIVATE)
                    .edit().putBoolean("scripts_guided", true).apply()
            }
            delay(400)
            guideController.start(guideSteps)
        }
    }

    // ---- 预览状态（） ----
    var isSurfaceAvailable by remember { mutableStateOf(false) }
    var isGameMuted by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }

    // ---- 自动设置（SettingsRepository 直读， 开关） ----
    var muteOnLaunch by remember { mutableStateOf(SettingsRepository.isMuteOnGameLaunch(context)) }
    var closeGameOnEnd by remember { mutableStateOf(SettingsRepository.isCloseGameAfterTask(context)) }
    var hwScreenOff by remember { mutableStateOf(SettingsRepository.isUseHardwareScreenOff(context)) }
    var showTouchPreview by remember { mutableStateOf(SettingsRepository.isShowTouchPreview(context)) }

    val tabs = remember { listOf(NarutoPanelTab.TASKS, NarutoPanelTab.LOG) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })
    var selectedTab by remember { mutableStateOf(NarutoPanelTab.TASKS) }

    LaunchedEffect(selectedTab) {
        val idx = tabs.indexOf(selectedTab)
        if (pagerState.currentPage != idx) pagerState.animateScrollToPage(idx)
    }
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = tabs[pagerState.currentPage]
    }

    fun saveProfile() = ProfileManager.save(context, profile)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            // --- 预览图区域（） ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
                    .then(rememberGuideTarget("preview", guideTargets))
            ) {
                if (!isFullscreen) {
                    VirtualDisplayPreview(
                        modifier = Modifier.fillMaxSize(),
                        isRunning = running,
                        currentTask = currentTask,
                        isSurfaceAvailable = isSurfaceAvailable,
                        onClick = { onFullscreenChange(true) }) {
                        PreviewSurface(
                            displayResolution = displayResolution,
                            onSurfaceAvailable = onPreviewSurfaceAvailable,
                            onSurfaceStateChange = { isSurfaceAvailable = it }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- 业务内容区域 ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(7f)
            ) {
                PanelHeader(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(rememberGuideTarget("panel", guideTargets))
                ) { page ->
                    when (tabs[page]) {
                        NarutoPanelTab.TASKS -> TaskListDetailLayout(
                            nodes = profile.tasks,
                            allTasks = allTasks,
                            selectedNodeId = selectedTaskEntry,
                            isEditMode = isEditMode,
                            isAddingTask = isAddingTask,
                            isProfileMode = isProfileMode,
                            profiles = profiles,
                            activeProfileId = currentProfileName,
                            onNodeEnabledChange = { entry, enabled ->
                                // 不可变替换，触发重组（修复勾选不刷新 bug）喵
                                profile = profile.copy(
                                    tasks = profile.tasks.map {
                                        if (it.entry == entry) it.copy(enabled = enabled) else it
                                    }.toMutableList()
                                )
                                saveProfile()
                            },
                            onNodeSelected = { selectedTaskEntry = it },
                            onNodeMove = { from, to ->
                                val list = profile.tasks.toMutableList()
                                if (from < 0 || to < 0 || from >= list.size || to >= list.size) return@TaskListDetailLayout
                                val item = list.removeAt(from)
                                list.add(to, item)
                                profile = profile.copy(tasks = list)
                                saveProfile()
                            },
                            onNodeRemove = { index ->
                                val list = profile.tasks.toMutableList()
                                if (index < 0 || index >= list.size) return@TaskListDetailLayout
                                list.removeAt(index)
                                profile = profile.copy(tasks = list)
                                saveProfile()
                            },
                            onToggleEditMode = { isEditMode = !isEditMode },
                            onToggleAddingTask = { isAddingTask = !isAddingTask },
                            onToggleProfileMode = {
                                // 切到配置模式时退出编辑/添加状态，避免界面混乱喵
                                isProfileMode = !isProfileMode
                                if (isProfileMode) {
                                    isEditMode = false
                                    isAddingTask = false
                                }
                            },
                            onSwitchProfile = { name ->
                                currentProfileName = name
                                selectedTaskEntry = null
                                SettingsRepository.setCurrentProfile(context, name)
                                profile = if (name == ProfileManager.DEFAULT_PROFILE_NAME) {
                                    ProfileManager.loadDefault(context, interfaceData)
                                } else {
                                    ProfileManager.load(context, name)
                                        ?: ProfileManager.Profile(name, mutableListOf()).also { ProfileManager.save(context, it) }
                                }
                                isProfileMode = false
                            },
                            onCreateProfile = { name ->
                                val p = ProfileManager.Profile(name, mutableListOf())
                                ProfileManager.save(context, p)
                                currentProfileName = name
                                profile = p
                                SettingsRepository.setCurrentProfile(context, name)
                                refreshProfiles()
                                isProfileMode = false
                            },
                            onDeleteProfile = { name ->
                                ProfileManager.delete(context, name)
                                refreshProfiles()
                                if (currentProfileName == name) {
                                    currentProfileName = ProfileManager.DEFAULT_PROFILE_NAME
                                    SettingsRepository.setCurrentProfile(context, ProfileManager.DEFAULT_PROFILE_NAME)
                                    profile = ProfileManager.loadDefault(context, interfaceData)
                                }
                            },
                            onRenameProfile = { old, new ->
                                if (ProfileManager.rename(context, old, new)) {
                                    if (currentProfileName == old) {
                                        currentProfileName = new
                                        SettingsRepository.setCurrentProfile(context, new)
                                    }
                                    refreshProfiles()
                                }
                            },
                            onAddNode = { task ->
                                profile.tasks.add(ProfileManager.ProfileTask(task.entry, true))
                                saveProfile()
                                isAddingTask = false
                            },
                            interfaceData = interfaceData,
                            modifier = Modifier.fillMaxSize()
                        )
                        NarutoPanelTab.LOG -> LogPanel(
                            logs = logs,
                            onClearLogs = onClearLogs,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // --- 底部操作栏（ 结构） ---
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(rememberGuideTarget("bottom", guideTargets)),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onStartProfile(currentProfileName) },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("开始任务", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = running,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("停止任务", maxLines = 1)
                    }
                    IconButton(
                        onClick = { showMoreActions = !showMoreActions },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多操作"
                        )
                    }
                }
            }
        }

        BackHandler(enabled = showMoreActions) {
            showMoreActions = false
        }
        BackHandler(enabled = isFullscreen) {
            onFullscreenChange(false)
        }

        if (showMoreActions) {
            BackgroundMoreActionsOverlay(
                onDismissRequest = { showMoreActions = false },
                isGameMuted = isGameMuted,
                onToggleGameSound = {
                    val newMuted = !isGameMuted
                    isGameMuted = newMuted
                    onToggleGameSound(newMuted)
                },
                onScreenOff = onScreenOff,
                onScreenOn = onScreenOn,
                onCaptureScreenshot = onScreenshot,
                onCloseApp = onCloseGame,
                muteOnGameLaunch = muteOnLaunch,
                onSetMuteOnLaunch = {
                    muteOnLaunch = it
                    SettingsRepository.setMuteOnGameLaunch(context, it)
                },
                closeGameOnTaskEnd = closeGameOnEnd,
                onSetCloseGameOnTaskEnd = {
                    closeGameOnEnd = it
                    SettingsRepository.setCloseGameAfterTask(context, it)
                },
                useHardwareScreenOff = hwScreenOff,
                onSetUseHardwareScreenOff = {
                    hwScreenOff = it
                    SettingsRepository.setUseHardwareScreenOff(context, it)
                },
                showTouchPreview = showTouchPreview,
                onSetShowTouchPreview = {
                    showTouchPreview = it
                    SettingsRepository.setShowTouchPreview(context, it)
                }
            )
        }

        // --- 全屏预览（，含触摸注入；画面填满全屏不裁切） ---
        if (isFullscreen) {
            // 全屏预览强制横屏（）喵
            DisposableEffect(Unit) {
                val originalOrientation = activity?.requestedOrientation
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                onDispose {
                    if (originalOrientation != null) {
                        activity?.requestedOrientation = originalOrientation
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                viewToVirtualDisplay(
                                    viewX = change.position.x,
                                    viewY = change.position.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                    bufferWidth = displayResolution.first,
                                    bufferHeight = displayResolution.second
                                ) { vx, vy ->
                                    when (event.type) {
                                        PointerEventType.Press -> onInjectTouch(MotionEvent.ACTION_DOWN, vx, vy)
                                        PointerEventType.Move -> {
                                            if (change.pressed) {
                                                onInjectTouch(MotionEvent.ACTION_MOVE, vx, vy)
                                            }
                                        }
                                        PointerEventType.Release -> onInjectTouch(MotionEvent.ACTION_UP, vx, vy)
                                    }
                                }
                                change.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 填满全屏（不裁切），坐标换算 viewToVirtualDisplay 已处理 16:9 letterbox 喵
                PreviewSurface(
                    displayResolution = displayResolution,
                    onSurfaceAvailable = onPreviewSurfaceAvailable,
                    onSurfaceStateChange = { isSurfaceAvailable = it },
                    modifier = Modifier.fillMaxSize()
                )
                if (showTouchPreview) {
                    TouchPreviewOverlay(
                        // 仅显示脚本触摸位置（引擎广播）；用户手动触摸不显示喵
                        markers = scriptTouchMarkers.map { arr ->
                            if (arr.size >= 3) PreviewTouchMarker(arr[1], arr[2], arr[0]) else null
                        }.filterNotNull(),
                        displayResolution = displayResolution,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                IconButton(
                    onClick = { onFullscreenChange(false) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "退出全屏",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 顶部信息条（任务/分辨率/操作提示）喵
                Text(
                    text = buildString {
                        append(if (running) "运行中" else "未运行")
                        if (currentTask.isNotBlank()) append(" · $currentTask")
                        append("  ${displayResolution.first}x${displayResolution.second}")
                        append("\n点击画面可操作游戏，触摸标记仅显示脚本操作")
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }

    if (isAddingTask) {
        AddTaskDialog(
            allTasks = allTasks,
            profile = profile,
            onDismiss = { isAddingTask = false },
            onAdd = { task ->
                profile.tasks.add(ProfileManager.ProfileTask(task.entry, true))
                saveProfile()
                isAddingTask = false
            }
        )
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("关闭游戏") },
            text = { Text("确定要关闭火影忍者游戏吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    onCloseGame()
                }) { Text("关闭", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("取消") }
            }
        )
    }

    // 首次操作引导（聚光灯）已提升到 MainActivity 全局渲染，避免底栏遮挡与坐标偏移喵
}

/**
 * 预览 Surface（ previewContent 核心逻辑）喵。
 * RGBA_8888 + setFixedSize(虚拟屏分辨率) + surfaceChanged 尺寸匹配后才发送——
 * 修复「必须切换分页才显示、否则黑屏」的问题。
 */
@Composable
private fun PreviewSurface(
    displayResolution: Pair<Int, Int>,
    onSurfaceAvailable: (Surface?) -> Unit,
    onSurfaceStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val currentResolution by rememberUpdatedState(displayResolution)

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFormat(PixelFormat.RGBA_8888)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceStateChange(true)
                        scope.launch {
                            delay(50)
                            val res = currentResolution
                            holder.setFixedSize(res.first, res.second)
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        val res = currentResolution
                        if (width == res.first && height == res.second) {
                            onSurfaceAvailable(holder.surface)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceStateChange(false)
                        onSurfaceAvailable(null)
                    }
                })
            }
        },
        modifier = modifier
    )
}

/**
 * 虚拟屏预览卡片（ VirtualDisplayPreview.kt，去掉 AppWatchdog/koin）喵。
 */
@Composable
private fun VirtualDisplayPreview(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    currentTask: String,
    isSurfaceAvailable: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val aspectRatio = 16f / 9f
        val widthFromHeight = maxHeight * aspectRatio
        val heightFromWidth = maxWidth / aspectRatio
        val (cardWidth, cardHeight) = if (widthFromHeight <= maxWidth) {
            widthFromHeight to maxHeight
        } else {
            maxWidth to heightFromWidth
        }

        Card(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                when {
                    !isRunning -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "未运行，点击预览全屏",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    !isSurfaceAvailable -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "等待画面…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 状态指示点（ 看门狗指示器，显示当前任务）喵
                val (dotColor, label) = if (isRunning) {
                    Color(0xFF4CAF50) to ("运行中" + if (currentTask.isNotBlank()) " · $currentTask" else "")
                } else {
                    Color(0xFF9E9E9E) to "空闲"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** 视图坐标 → 虚拟屏坐标（ viewToVirtualDisplay）喵 */
private inline fun viewToVirtualDisplay(
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    block: (vx: Int, vy: Int) -> Unit,
) {
    val bufferW = bufferWidth.toFloat()
    val bufferH = bufferHeight.toFloat()
    val scale = minOf(viewWidth / bufferW, viewHeight / bufferH)
    val offsetX = (viewWidth - bufferW * scale) / 2f
    val offsetY = (viewHeight - bufferH * scale) / 2f
    val vx = ((viewX - offsetX) / scale).toInt()
    val vy = ((viewY - offsetY) / scale).toInt()
    if (vx < 0 || vx >= bufferW.toInt() || vy < 0 || vy >= bufferH.toInt()) return
    block(vx, vy)
}

/**
 * 更多操作浮层（ BackgroundMoreActionsOverlay.kt）喵。
 */
@Composable
private fun BackgroundMoreActionsOverlay(
    onDismissRequest: () -> Unit,
    isGameMuted: Boolean,
    onToggleGameSound: () -> Unit,
    onScreenOff: () -> Unit,
    onScreenOn: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onCloseApp: () -> Unit,
    muteOnGameLaunch: Boolean,
    onSetMuteOnLaunch: (Boolean) -> Unit,
    closeGameOnTaskEnd: Boolean,
    onSetCloseGameOnTaskEnd: (Boolean) -> Unit,
    useHardwareScreenOff: Boolean,
    onSetUseHardwareScreenOff: (Boolean) -> Unit,
    showTouchPreview: Boolean,
    onSetShowTouchPreview: (Boolean) -> Unit,
) {
    val overlayInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onClick = onDismissRequest
            )
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
                .clickable(
                    interactionSource = cardInteractionSource,
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "快捷操作",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = Icons.Filled.PowerSettingsNew,
                        label = "关屏",
                        onClick = onScreenOff,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.Filled.Lightbulb,
                        label = "亮屏",
                        onClick = onScreenOn,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.Filled.ExitToApp,
                        label = "关闭游戏",
                        onClick = onCloseApp,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = if (isGameMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        label = if (isGameMuted) "已静音" else "静音游戏",
                        onClick = onToggleGameSound,
                        modifier = Modifier.weight(1f),
                        containerColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.Filled.Screenshot,
                        label = "截图",
                        onClick = onCaptureScreenshot,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "自动设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                SettingSwitchRow(
                    icon = Icons.Filled.NotificationsPaused,
                    label = "启动任务时静音",
                    checked = muteOnGameLaunch,
                    onCheckedChange = onSetMuteOnLaunch
                )
                SettingSwitchRow(
                    icon = Icons.Filled.Cancel,
                    label = "任务结束后关闭游戏",
                    checked = closeGameOnTaskEnd,
                    onCheckedChange = onSetCloseGameOnTaskEnd
                )
                SettingSwitchRow(
                    icon = Icons.Filled.StayCurrentPortrait,
                    label = "硬件熄屏",
                    checked = useHardwareScreenOff,
                    onCheckedChange = onSetUseHardwareScreenOff
                )
                SettingSwitchRow(
                    icon = Icons.Filled.TouchApp,
                    label = "显示触摸预览",
                    checked = showTouchPreview,
                    onCheckedChange = onSetShowTouchPreview
                )
            }
        }
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(4.dp),
        color = containerColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        border = BorderStroke(0.5.dp, containerColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = containerColor.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AddTaskDialog(
    allTasks: List<MaaTask>,
    profile: ProfileManager.Profile,
    onDismiss: () -> Unit,
    onAdd: (MaaTask) -> Unit
) {
    val added = profile.tasks.mapNotNull { pt -> allTasks.find { it.entry == pt.entry } }
    val notAdded = allTasks.filter { t -> profile.tasks.none { it.entry == t.entry } }
    var showAdded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加任务") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 已添加任务（可展开，展开后可以重复添加）喵
                if (added.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdded = !showAdded },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已添加（${added.size}）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (showAdded) "收起" else "展开（可重复添加）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (showAdded) {
                    items(added) { task ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(task) },
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    task.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "已添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                // 可添加任务
                if (notAdded.isNotEmpty()) {
                    item {
                        Text(
                            "可添加",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(notAdded) { task ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(task) },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                task.name,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } }
    )
}

// ---- 触摸预览标记（ PreviewTouchMarker.kt） ----

/** 触摸标记：虚拟屏坐标 + 动作 + 创建时间，用于在预览上显示触摸痕迹喵 */
data class PreviewTouchMarker(
    val x: Int,
    val y: Int,
    val action: Int,
    val createdAtMs: Long = SystemClock.elapsedRealtime()
) {
    companion object {
        const val TTL_MS = 600L
    }
}

private val MarkerGreen = Color(0xFF81C784)
private val MarkerAmber = Color(0xFFFFD54F)
private val MarkerRed = Color(0xFFE57373)

/**
 * 触摸预览覆盖层（ TouchPreviewOverlay.kt）喵。
 */
@Composable
private fun TouchPreviewOverlay(
    markers: List<PreviewTouchMarker>,
    displayResolution: Pair<Int, Int>,
    modifier: Modifier = Modifier,
) {
    val width = displayResolution.first
    val height = displayResolution.second
    Canvas(modifier = modifier) {
        val now = SystemClock.elapsedRealtime()
        val maxX = (width - 1).coerceAtLeast(1)
        val maxY = (height - 1).coerceAtLeast(1)

        // 滑动画线：MOVE 与上一个点之间画连线（避免贪吃蛇式点阵）喵
        var prevMove: Pair<Offset, Offset>? = null

        markers.forEach { marker ->
            val age = (now - marker.createdAtMs).coerceAtLeast(0L)
            val progress = (age / PreviewTouchMarker.TTL_MS.toFloat()).coerceIn(0f, 1f)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEach

            val center = Offset(
                x = size.width * marker.x.coerceIn(0, maxX) / maxX.toFloat(),
                y = size.height * marker.y.coerceIn(0, maxY) / maxY.toFloat()
            )

            when (marker.action) {
                MotionEvent.ACTION_DOWN -> {
                    drawCircle(
                        color = MarkerGreen.copy(alpha = alpha * 0.3f),
                        radius = (8.dp.toPx() + (12.dp.toPx() * progress)),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx() * alpha)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MarkerGreen.copy(alpha = alpha * 0.8f),
                                MarkerGreen.copy(alpha = 0f)
                            ),
                            center = center,
                            radius = 12.dp.toPx()
                        ),
                        radius = 12.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = alpha * 0.9f),
                        radius = 2.5.dp.toPx(),
                        center = center
                    )
                    prevMove = center to center
                }

                MotionEvent.ACTION_MOVE -> {
                    // 与上一个点连线，形成滑动轨迹喵
                    val last = prevMove?.second
                    if (last != null && last != center) {
                        drawLine(
                            color = MarkerAmber.copy(alpha = alpha * 0.7f),
                            start = last,
                            end = center,
                            strokeWidth = 3.dp.toPx() * alpha
                        )
                    }
                    drawCircle(
                        color = MarkerAmber.copy(alpha = alpha * 0.5f),
                        radius = 5.dp.toPx(),
                        center = center
                    )
                    prevMove = center to center
                }

                MotionEvent.ACTION_UP -> {
                    drawCircle(
                        color = MarkerRed.copy(alpha = alpha * 0.6f),
                        radius = (6.dp.toPx() + (18.dp.toPx() * progress)),
                        center = center,
                        style = Stroke(width = 2.dp.toPx() * alpha)
                    )
                    drawCircle(
                        color = MarkerRed.copy(alpha = alpha * 0.8f),
                        radius = 3.dp.toPx() * (1f - progress),
                        center = center
                    )
                    prevMove = null
                }
            }
        }
    }
}