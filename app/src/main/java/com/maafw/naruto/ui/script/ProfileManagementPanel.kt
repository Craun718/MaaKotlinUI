package com.maafw.naruto.ui.script

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.theme.MaaDesignTokens

/**
 * 任务配置管理面板喵～
 * 列出所有任务配置（profile），可切换当前配置、新建空配置、删除/重命名。
 * 定时任务读取的 profileId 就是这里的配置名，可被正常调用。
 */
@Composable
fun ProfileManagementPanel(
    profiles: List<String>,
    activeProfileId: String,
    onSwitchProfile: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    allTasks: List<MaaTask>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingProfile by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text("任务配置", style = MaterialTheme.typography.titleMedium)
        Text(
            "切换不同任务列表；定时任务按配置名执行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(profiles, key = { it }) { name ->
                val isActive = name == activeProfileId
                val tasks = ProfileManager.load(context, name)?.tasks.orEmpty()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchProfile(name) },
                    shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${tasks.count { it.enabled }} 个启用 / ${tasks.size} 个任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isActive) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (name != ProfileManager.DEFAULT_PROFILE_NAME) {
                            IconButton(onClick = { renamingProfile = name }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDeleteProfile(name) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Default.Delete, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建任务配置")
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建任务配置") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：日常、周胜、决斗场") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty() && trimmed !in profiles) {
                        onCreateProfile(trimmed)
                    }
                    showCreateDialog = false
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            }
        )
    }

    renamingProfile?.let { oldName ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { renamingProfile = null },
            title = { Text("重命名配置") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty() && trimmed != oldName) {
                        onRenameProfile(oldName, trimmed)
                    }
                    renamingProfile = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingProfile = null }) { Text("取消") }
            }
        )
    }
}