package com.maafw.naruto.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.maafw.naruto.data.log.LogExporter
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.ui.components.CollapsibleSection
import com.maafw.naruto.ui.components.ListItemDivider
import com.maafw.naruto.ui.components.MaaTopAppBar
import com.maafw.naruto.ui.components.SectionHeader
import com.maafw.naruto.ui.components.SettingRow
import com.maafw.naruto.ui.components.SettingsGroupCard
import com.maafw.naruto.ui.theme.MaaDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页喵～
 *  SettingsView.kt 的分区结构：
 * 显示设置 / 运行设置 / 通知设置 / 日志 / 关于，每个分区用 CollapsibleSection + SettingsGroupCard。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    logBuffer: MutableList<String>,
    theme: String,
    onThemeChange: (String) -> Unit,
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onResolutionChange: (String) -> Unit,
    onRunModeChange: (String) -> Unit,
    onCaptureLogcat: (() -> String?)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logUpdateTime = remember { LogExporter.sourceLastModifiedText(context) }

    // 分辨率 / 运行模式（设置页内部 state，保存时回调）喵
    var resolution by remember { mutableStateOf(SettingsRepository.getResolution(context)) }
    var runMode by remember { mutableStateOf(SettingsRepository.getRunMode(context)) }
    var scale by remember { mutableStateOf(uiScale) }
    var customW by remember { mutableStateOf(SettingsRepository.getCustomWidth(context)) }
    var customH by remember { mutableStateOf(SettingsRepository.getCustomHeight(context)) }
    var customDpi by remember { mutableStateOf(SettingsRepository.getCustomDpi(context)) }
    var scheduleWakeOn by remember { mutableStateOf(SettingsRepository.isScheduleWakeOn(context)) }

    // 通知推送配置 state 喵
    var pushChannel by remember { mutableStateOf(SettingsRepository.getPushChannel(context)) }
    var pushMiaoToken by remember { mutableStateOf(SettingsRepository.getPushMiaotixingToken(context)) }
    var pushServerKey by remember { mutableStateOf(SettingsRepository.getPushServerChanKey(context)) }
    var pushDingToken by remember { mutableStateOf(SettingsRepository.getPushDingTalkToken(context)) }
    var pushSmtpHost by remember { mutableStateOf(SettingsRepository.getPushSmtpHost(context)) }
    var pushSmtpPort by remember { mutableStateOf(SettingsRepository.getPushSmtpPort(context).toString()) }
    var pushSmtpUser by remember { mutableStateOf(SettingsRepository.getPushSmtpUser(context)) }
    var pushSmtpPass by remember { mutableStateOf(SettingsRepository.getPushSmtpPass(context)) }
    var pushSmtpTo by remember { mutableStateOf(SettingsRepository.getPushSmtpTo(context)) }
    var pushWebhookUrl by remember { mutableStateOf(SettingsRepository.getPushWebhookUrl(context)) }
    var pushWebhookBody by remember { mutableStateOf(SettingsRepository.getPushWebhookBody(context)) }

    // 第三方通知设置页（按钮入口 → 独立页面）喵
    var showNotificationSettings by remember { mutableStateOf(false) }

    /** 导出最新日志到 /storage/emulated/0/maa日志（带时间戳文件名）喵 */
    fun exportLog() {
        if (!LogExporter.hasStoragePermission()) {
            // 无公共存储权限：提示并继续导出（fallback 到应用私有目录），确保能拿到日志喵
            Toast.makeText(context, "未授予存储权限，日志将导出到应用私有目录，可在设置页授权后导出到公共目录", Toast.LENGTH_LONG).show()
        }
        scope.launch {
            val maafwResult = withContext(Dispatchers.IO) { LogExporter.exportLatest(context) }
            val appResult = withContext(Dispatchers.IO) { LogExporter.exportAppLog(context, logBuffer.toList()) }
            val logcatResult = withContext(Dispatchers.IO) {
                // 优先用引擎（shell 进程）抓取全量 logcat（含引擎自身日志），否则用本地 logcat 喵
                val engineText = onCaptureLogcat?.invoke()
                if (engineText != null) {
                    LogExporter.exportLogcatText(context, engineText)
                } else {
                    LogExporter.exportLogcat(context)
                }
            }
            val parts = mutableListOf<String>()
            maafwResult.onSuccess { parts += "引擎日志: $it" }
                .onFailure { e ->
                    // maafw.log 不存在属于"引擎未运行"，不算导出失败喵
                    parts += if (e.message?.contains("不存在") == true) "引擎日志: 无（引擎未运行，无 maafw.log）" else "引擎日志导出失败: ${e.message}"
                }
            appResult.onSuccess { parts += "应用日志: $it" }.onFailure { parts += "应用日志导出失败: ${it.message}" }
            logcatResult.onSuccess { parts += "系统日志: $it" }
                .onFailure { parts += "系统日志导出失败: ${it.message}（可连接引擎/授予root后重试）" }
            // 汇总提示，明确成功与失败喵
            val successCount = parts.count { !it.contains("失败") && !it.contains("无（") }
            Toast.makeText(
                context,
                if (successCount > 0) parts.joinToString("\n") else "导出失败，请检查存储权限后重试",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    var keepScreenOn by remember { mutableStateOf(SettingsRepository.isKeepScreenOn(context)) }
    var showFloatingLog by remember { mutableStateOf(SettingsRepository.isShowFloatingLog(context)) }
    var autoStartShizuku by remember { mutableStateOf(SettingsRepository.isAutoStartShizuku(context)) }
    var closeGameAfterTask by remember { mutableStateOf(SettingsRepository.isCloseGameAfterTask(context)) }
    var themeExpanded by remember { mutableStateOf(false) }

    var notificationEnabled by remember { mutableStateOf(SettingsRepository.isNotificationEnabled(context)) }
    var notificationSound by remember { mutableStateOf(SettingsRepository.isNotificationSound(context)) }
    var notificationVibrate by remember { mutableStateOf(SettingsRepository.isNotificationVibrate(context)) }

    var logs by remember { mutableStateOf(logBuffer.toList()) }
    LaunchedEffect(logBuffer) {
        logs = logBuffer.toList()
    }

    if (showNotificationSettings) {
        NotificationSettingsScreen(onBack = { showNotificationSettings = false })
    } else {
    Scaffold(
        topBar = {
            MaaTopAppBar(title = "设置")
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sectionGap)
        ) {
            // 显示设置（ settings_section_display）
            item {
                CollapsibleSection(
                    title = "显示设置",
                    sectionKey = "settings_section_display",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "主题模式",
                            description = "跟随系统 / 浅色 / 深色",
                            trailing = {
                                Box {
                                    Button(
                                        onClick = { themeExpanded = true },
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Text(themeName(theme), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    DropdownMenu(
                                        expanded = themeExpanded,
                                        onDismissRequest = { themeExpanded = false }
                                    ) {
                                        listOf(
                                            SettingsRepository.THEME_SYSTEM to "跟随系统",
                                            SettingsRepository.THEME_LIGHT to "浅色",
                                            SettingsRepository.THEME_DARK to "深色",
                                            SettingsRepository.THEME_MONET to "莫奈（壁纸取色）"
                                        ).forEach { (value, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    SettingsRepository.setTheme(context, value)
                                                    onThemeChange(value)
                                                    themeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "屏幕常亮",
                            description = "运行任务时保持屏幕点亮",
                            trailing = {
                                Switch(
                                    checked = keepScreenOn,
                                    onCheckedChange = {
                                        keepScreenOn = it
                                        SettingsRepository.setKeepScreenOn(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "页面缩放",
                            description = "调整界面整体大小（含间距）${(scale * 100).toInt()}%（松手生效）",
                            trailing = {
                                Slider(
                                    value = scale,
                                    onValueChange = { scale = it },
                                    onValueChangeFinished = {
                                        // 松手后才应用全局缩放，避免拖动时界面变化导致滑条脱手喵
                                        onUiScaleChange(scale)
                                        SettingsRepository.setUiScale(context, scale)
                                    },
                                    valueRange = 0.7f..1.3f,
                                    modifier = Modifier.width(120.dp)
                                )
                            }
                        )
                    }
                }
            }

            // 运行设置
            item {
                CollapsibleSection(
                    title = "运行设置",
                    sectionKey = "settings_section_run",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "自动启动 Shizuku",
                            description = "应用启动时尝试拉起 Shizuku 服务",
                            trailing = {
                                Switch(
                                    checked = autoStartShizuku,
                                    onCheckedChange = {
                                        autoStartShizuku = it
                                        SettingsRepository.setAutoStartShizuku(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务结束后关闭游戏",
                            description = "任务自然结束后关闭火影忍者",
                            trailing = {
                                Switch(
                                    checked = closeGameAfterTask,
                                    onCheckedChange = {
                                        closeGameAfterTask = it
                                        SettingsRepository.setCloseGameAfterTask(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "悬浮日志",
                            description = "在屏幕上显示运行日志（后续实现）",
                            trailing = {
                                Switch(
                                    checked = showFloatingLog,
                                    onCheckedChange = {
                                        showFloatingLog = it
                                        SettingsRepository.setShowFloatingLog(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        // 分辨率：上下布局，按钮放下方（不再挤在右侧）喵
                        SettingRow(
                            title = "虚拟屏分辨率",
                            description = "720p / 1080p / 自定义宽高与 DPI"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                SettingsRepository.RES_720P to "720p",
                                SettingsRepository.RES_1080P to "1080p",
                                SettingsRepository.RES_CUSTOM to "自定义"
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = resolution == value,
                                    onClick = {
                                        resolution = value
                                        SettingsRepository.setResolution(context, value)
                                        onResolutionChange(value)
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        if (resolution == SettingsRepository.RES_CUSTOM) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PushTextField(
                                    label = "宽度",
                                    value = customW.toString(),
                                    onChange = { customW = it.toIntOrNull() ?: 1280; SettingsRepository.setCustomResolution(context, customW, customH, customDpi) },
                                    modifier = Modifier.weight(1f)
                                )
                                PushTextField(
                                    label = "高度",
                                    value = customH.toString(),
                                    onChange = { customH = it.toIntOrNull() ?: 720; SettingsRepository.setCustomResolution(context, customW, customH, customDpi) },
                                    modifier = Modifier.weight(1f)
                                )
                                PushTextField(
                                    label = "DPI",
                                    value = customDpi.toString(),
                                    onChange = { customDpi = it.toIntOrNull() ?: 160; SettingsRepository.setCustomResolution(context, customW, customH, customDpi) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        ListItemDivider()
                        SettingRow(
                            title = "运行模式",
                            description = "Shizuku（推荐）或 Root（需已 root）",
                            trailing = {
                                Row {
                                    listOf(
                                        SettingsRepository.RUN_MODE_SHIZUKU to "Shizuku",
                                        SettingsRepository.RUN_MODE_ROOT to "Root"
                                    ).forEach { (value, label) ->
                                        FilterChip(
                                            selected = runMode == value,
                                            onClick = {
                                                runMode = value
                                                SettingsRepository.setRunMode(context, value)
                                                onRunModeChange(value)
                                            },
                                            label = { Text(label) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "定时任务后台唤醒",
                            description = "锁屏/应用未启动时由系统精确唤醒并执行定时任务",
                            trailing = {
                                Switch(
                                    checked = scheduleWakeOn,
                                    onCheckedChange = {
                                        scheduleWakeOn = it
                                        SettingsRepository.setScheduleWakeOn(context, it)
                                        // 立即重注册所有策略，使开关生效喵
                                        val strategies = com.maafw.naruto.schedule.data.ScheduleStrategyRepository(context).load()
                                        com.maafw.naruto.schedule.ScheduleHelper.rescheduleStrategies(context, strategies)
                                    }
                                )
                            }
                        )
                    }
                }
            }

            // 通知设置（ settings_section_notification）
            item {
                CollapsibleSection(
                    title = "通知设置",
                    sectionKey = "settings_section_notification",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "启用通知",
                            description = "任务完成或失败时发送通知",
                            trailing = {
                                Switch(
                                    checked = notificationEnabled,
                                    onCheckedChange = {
                                        notificationEnabled = it
                                        SettingsRepository.setNotificationEnabled(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "通知声音",
                            description = "通知时播放提示音",
                            trailing = {
                                Switch(
                                    checked = notificationSound,
                                    onCheckedChange = {
                                        notificationSound = it
                                        SettingsRepository.setNotificationSound(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "通知振动",
                            description = "通知时振动",
                            trailing = {
                                Switch(
                                    checked = notificationVibrate,
                                    onCheckedChange = {
                                        notificationVibrate = it
                                        SettingsRepository.setNotificationVibrate(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "第三方通知",
                            description = "任务完成/出错时推送（喵提醒/Server酱/钉钉/SMTP/Webhook）",
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { showNotificationSettings = true }
                        )
                    }
                }
            }

            // 日志（ settings_section_log）
            item {
                CollapsibleSection(
                    title = "运行日志",
                    sectionKey = "settings_section_log",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "导出日志",
                            description = buildString {
                                append("复制最新 maafw.log 到 /storage/emulated/0/maa日志（带时间戳）")
                                logUpdateTime?.let { append("\n日志更新于 $it") }
                            },
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = "导出日志",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = { exportLog() }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "清空日志",
                            description = "删除所有运行日志",
                            onClick = {
                                synchronized(logBuffer) {
                                    logBuffer.clear()
                                    logs = emptyList()
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(MaaDesignTokens.Spacing.sm))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 320.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            if (logs.isEmpty()) {
                                item {
                                    Text(
                                        "暂无日志喵～",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                items(logs) { log ->
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 关于（ settings_section_about）
            item {
                CollapsibleSection(
                    title = "关于",
                    sectionKey = "settings_section_about",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "应用版本",
                            description = com.maafw.naruto.BuildConfig.VERSION_NAME
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "MaaFramework",
                            description = "MAAFW 火影忍者手游 Android 适配版"
                        )
                    }
                }
            }

            item {
                Text(
                    "By.白川～",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }
}

private fun themeName(theme: String): String = when (theme) {
    SettingsRepository.THEME_LIGHT -> "浅色"
    SettingsRepository.THEME_DARK -> "深色"
    SettingsRepository.THEME_MONET -> "莫奈"
    else -> "跟随系统"
}

@Composable
private fun PushTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}