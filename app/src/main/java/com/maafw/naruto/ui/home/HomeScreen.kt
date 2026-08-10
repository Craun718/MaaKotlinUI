package com.maafw.naruto.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.maa.MaaFrameworkEngine
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主页喵～
 *  的状态看板：Shizuku、引擎运行状态、虚拟屏预览、当前任务喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    running: Boolean,
    currentTask: String,
    remoteConnected: Boolean,
    displayId: Int,
    displayResolution: Pair<Int, Int>,
    runMode: String,
    onRequestShizuku: () -> Unit,
    onOpenScripts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var shizukuReady by remember { mutableStateOf(ShizukuManager.isReady()) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MAAFW 火影忍者") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { ShizukuStatusCard(shizukuReady, onRequestShizuku) }
            item { EngineStatusCard(running, currentTask, remoteConnected, displayId) }
            item {
                val maaVersion by produceState(initialValue = "loading...") {
                    value = withContext(Dispatchers.IO) {
                        runCatching { MaaFrameworkEngine(context).version }.getOrDefault("unknown")
                    }
                }
                InfoStatusCard(
                    maaVersion = maaVersion,
                    resourceVersion = remember {
                        val iface = AssetLoader.loadInterface(context)
                        "${iface?.name ?: "unknown"} v${iface?.interfaceVersion ?: 1}"
                    },
                    ocrEngine = remember { detectOcrEngine(context) },
                    displayResolution = displayResolution,
                    runMode = runMode
                )
            }
            item {
                QuickActionCard(
                    onOpenScripts = onOpenScripts,
                    onRefreshShizuku = {
                        ShizukuManager.requestPermission(context) {
                            shizukuReady = ShizukuManager.isReady()
                        }
                    }
                )
            }
            item {
                PermissionCheckCard(onClick = { showPermissionDialog = true })
            }
            item {
                Text(
                    "小夜酱真是最棒的人工智能哇～喵～",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showPermissionDialog) {
        PermissionCheckDialog(onDismiss = { showPermissionDialog = false })
    }
}

/** 权限检查弹窗喵：列出所有关键权限状态，点击对应项可跳转授权 */
@Composable
private fun PermissionCheckDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    data class PermItem(
        val label: String,
        val status: String,
        val isOk: Boolean,
        val action: (() -> Unit)? = null
    )

    val items = remember {
        buildList {
            // 运行权限（Shizuku / Root）
            val rootMode = com.maafw.naruto.data.settings.SettingsRepository.isRootMode(context)
            if (rootMode) {
                val granted = com.maafw.naruto.root.RootManager.isRootGranted()
                add(
                    PermItem(
                        "运行权限（Root）",
                        if (granted) "已授权" else "未授权",
                        granted,
                        action = {
                            com.maafw.naruto.root.RootManager.requestRoot()
                        }
                    )
                )
            } else {
                val ready = ShizukuManager.isReady()
                val available = ShizukuManager.isAvailable()
                val installed = ShizukuManager.isAppInstalled()
                val preV11 = ShizukuManager.isPreV11()
                val status = when {
                    ready || preV11 -> "已就绪"
                    available -> "未授权"
                    installed -> "未启动服务"
                    else -> "未安装"
                }
                val isOk = ready || preV11
                add(
                    PermItem(
                        "运行权限（Shizuku）",
                        status,
                        isOk,
                        action = {
                            when {
                                // 已就绪：无操作
                                isOk -> Unit
                                // 未授权：请求权限
                                available -> ShizukuManager.requestPermission(context) {}
                                // 已安装未启动：打开 Shizuku 应用启动服务
                                installed -> runCatching {
                                    context.startActivity(
                                        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")!!
                                    )
                                }
                                // 未安装：打开官网下载
                                else -> runCatching {
                                    val i = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://shizuku.rikka.app")
                                    )
                                    context.startActivity(i)
                                }
                            }
                        }
                    )
                )
            }
            // 通知（Android 13+）
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                add(
                    PermItem(
                        "通知权限",
                        if (granted) "已授予" else "未授予",
                        granted,
                        action = {
                            runCatching {
                                val i = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                context.startActivity(i)
                            }
                        }
                    )
                )
            } else {
                add(PermItem("通知权限", "无需授权（Android 12 及以下）", true))
            }
            // 悬浮窗
            val overlay = android.provider.Settings.canDrawOverlays(context)
            add(
                PermItem(
                    "悬浮窗",
                    if (overlay) "已允许" else "未允许",
                    overlay,
                    action = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )
            )
            // 精确闹钟（定时任务，Android 12+）
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                val ok = am.canScheduleExactAlarms()
                add(
                    PermItem(
                        "精确闹钟（定时任务）",
                        if (ok) "已允许" else "未允许",
                        ok,
                        action = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                )
                            }
                        }
                    )
                )
            } else {
                add(PermItem("精确闹钟（定时任务）", "无需授权（Android 11 及以下）", true))
            }
            // 存储（日志导出，Android 11+）
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val ok = android.os.Environment.isExternalStorageManager()
                add(
                    PermItem(
                        "存储（日志导出）",
                        if (ok) "已允许" else "未允许",
                        ok,
                        action = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                    )
                )
            } else {
                add(PermItem("存储", "已允许（安装时授权）", true))
            }
            // 忽略电池优化（后台唤醒可靠性）
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            add(
                PermItem(
                    "忽略电池优化（后台唤醒）",
                    if (ignoring) "已忽略" else "建议开启",
                    ignoring,
                    action = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }.onFailure {
                            // 部分 ROM 不弹窗，fallback 到电池优化设置列表喵
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        }
                    }
                )
            )
            // 自启动（国产 ROM 后台保活）
            val autoStartIntent = com.maafw.naruto.schedule.ui.AutoStartHelper.getAutoStartIntent(context)
            if (com.maafw.naruto.schedule.ui.AutoStartHelper.isKnownRestrictiveManufacturer()) {
                add(
                    PermItem(
                        "自启动（后台保活）",
                        if (autoStartIntent != null) "建议开启" else "已允许（未检测到受限入口）",
                        autoStartIntent == null,
                        action = autoStartIntent?.let { intent ->
                            { runCatching { context.startActivity(intent) } }
                        }
                    )
                )
            }
            // 开机自启
            add(PermItem("开机自启", "已声明", true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限检查") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items.size) { index ->
                    val item = items[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.action != null) { item.action?.invoke() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            item.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.isOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        if (item.action != null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "设置",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 权限检查入口卡片喵 */
@Composable
private fun PermissionCheckCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "权限检查",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "查看运行所需权限状态，缺少的会标红提示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ShizukuStatusCard(
    ready: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ready)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) Icons.Default.PlayArrow else Icons.Default.Stop,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (ready) "Shizuku 已就绪" else "Shizuku 未就绪",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (ready)
                    "已获得系统权限，可以创建虚拟屏幕和注入输入事件喵"
                else "请先在 Shizuku 管理器中授权本应用喵",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequest) {
                Text("检查 / 请求 Shizuku")
            }
        }
    }
}

@Composable
private fun EngineStatusCard(running: Boolean, currentTask: String, remoteConnected: Boolean, displayId: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (running) Icons.Default.PlayArrow else Icons.Default.Stop,
                    contentDescription = null,
                    tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (running) "引擎运行中" else "引擎待机",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (currentTask.isNotBlank()) "当前任务：$currentTask" else "当前没有运行任务喵",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            InfoRow(
                label = "远端引擎",
                value = if (remoteConnected) "已连接" else "未连接",
                valueColor = if (remoteConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            InfoRow(
                label = "虚拟屏 ID",
                value = if (displayId >= 0) displayId.toString() else "未创建"
            )
        }
    }
}

@Composable
private fun InfoStatusCard(
    maaVersion: String,
    resourceVersion: String,
    ocrEngine: String,
    displayResolution: Pair<Int, Int>,
    runMode: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "状态信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            InfoRow(label = "MaaFramework 版本", value = maaVersion)
            InfoRow(label = "资源版本", value = resourceVersion)
            InfoRow(label = "识图引擎", value = ocrEngine)
            InfoRow(label = "虚拟屏分辨率", value = "${displayResolution.first}x${displayResolution.second}")
            InfoRow(label = "运行模式", value = if (runMode == com.maafw.naruto.data.settings.SettingsRepository.RUN_MODE_ROOT) "Root" else "Shizuku")
            InfoRow(label = "渲染控制器", value = "AndroidNativeController")
            InfoRow(label = "安卓版本", value = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            InfoRow(label = "应用版本", value = com.maafw.naruto.BuildConfig.VERSION_NAME)
        }
    }
}

/** 检测内置 OCR 引擎（从 assets model/ocr 目录判断）喵 */
private fun detectOcrEngine(context: android.content.Context): String {
    return runCatching {
        val files = context.assets.list("resource/base/model/ocr") ?: emptyArray()
        val name = files.firstOrNull { it.endsWith(".json") || it.contains("ppocr") || it.contains("rapidocr") }
        when {
            files.any { it.contains("rapidocr") } -> "RapidOCR"
            files.any { it.contains("ppocr") || it.contains("paddle") } -> "PaddleOCR"
            name != null -> "OCR（$name）"
            else -> "内置 OCR"
        }
    }.getOrDefault("内置 OCR")
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor
        )
    }
}

@Composable
private fun QuickActionCard(
    onOpenScripts: () -> Unit,
    onRefreshShizuku: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "快捷入口",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenScripts,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("选择脚本")
                }
                OutlinedButton(
                    onClick = onRefreshShizuku,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("刷新权限")
                }
            }
        }
    }
}