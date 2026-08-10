package com.maafw.naruto.ui.script

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaOption
import com.maafw.naruto.model.MaaTask

/**
 * 右侧任务配置面板喵～
 *  的 ConfigurationPanel：显示任务说明 + 选项编辑器。
 */
@Composable
fun TaskConfigPanel(
    selectedTask: MaaTask?,
    interfaceData: MaaInterface?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val taskOptions = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(selectedTask) {
        taskOptions.clear()
        selectedTask?.let { t ->
            SettingsRepository.getTaskConfig(context, t.entry).options.forEach { (k, v) -> taskOptions[k] = v }
            t.option?.forEach { name ->
                if (!taskOptions.contains(name)) {
                    val opt = interfaceData?.option?.get(name)
                    when (opt?.type) {
                        "input" -> opt.inputs?.firstOrNull()?.let { taskOptions[name] = it.default }
                        else -> opt?.let { taskOptions[name] = it.defaultCase }
                    }
                }
            }
        }
    }
    DisposableEffect(selectedTask?.entry) {
        onDispose { selectedTask?.let { SettingsRepository.setTaskOptions(context, it.entry, taskOptions.toMap()) } }
    }

    if (selectedTask == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "点击左侧任务查看和编辑设置喵",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(selectedTask.name, style = MaterialTheme.typography.titleMedium)
        if (!selectedTask.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                selectedTask.description.replace(Regex("""\[.*?\]"""), ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!selectedTask.option.isNullOrEmpty()) {
            Spacer(Modifier.height(12.dp))
            selectedTask.option.forEach { name ->
                val opt = interfaceData?.option?.get(name) ?: return@forEach
                OptionEditor(name, opt, taskOptions[name]) {
                    taskOptions[name] = it
                    SettingsRepository.setTaskOptions(context, selectedTask.entry, taskOptions.toMap())
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun OptionEditor(name: String, option: MaaOption, selected: String?, onSelected: (String) -> Unit) {
    val current = selected ?: option.defaultCase
    when (option.type) {
        "switch" -> {
            val yesCase = option.cases.getOrNull(0)?.name ?: "Yes"
            val noCase = option.cases.getOrNull(1)?.name ?: "No"
            val checked = current == yesCase
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(option.description.ifEmpty { name }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = checked, onCheckedChange = { onSelected(if (it) yesCase else noCase) })
            }
        }
        "input" -> {
            val input = option.inputs?.firstOrNull() ?: return
            var text by remember(name, current) { mutableStateOf(current) }
            Column(Modifier.fillMaxWidth()) {
                Text(input.label.ifEmpty { name }, style = MaterialTheme.typography.bodyMedium)
                if (input.description.isNotBlank()) {
                    Text(input.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onSelected(it) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (input.pipelineType == "int" || input.pipelineType == "number") KeyboardType.Number else KeyboardType.Text
                    ),
                    singleLine = true
                )
            }
        }
        else -> {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Text(option.description.ifEmpty { name }, style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
                        Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        option.cases.forEach { case ->
                            DropdownMenuItem(text = { Text(case.name) }, onClick = { onSelected(case.name); expanded = false })
                        }
                    }
                }
            }
        }
    }
}