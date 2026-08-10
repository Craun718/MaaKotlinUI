package com.maafw.naruto.ui.script

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.model.MaaTask

/**
 * 左侧任务列表喵～
 *  TaskListPanel.kt：
 * - 拖拽排序（ReorderableColumn）替换为 上/下移按钮 + LazyColumn（未引入 reorderable 依赖）
 * - TaskChainNode 替换为我们的 ProfileTask + MaaTask
 */
@Composable
fun TaskListPanel(
    nodes: List<ProfileManager.ProfileTask>,
    allTasks: List<MaaTask>,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onNodeRemove: (Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // 任务配置按钮 - 在编辑任务按钮上方（ 配置切换）喵
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleProfileMode() },
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isProfileMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.dp,
                if (isProfileMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isProfileMode) 2.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isProfileMode) Icons.Default.Check else Icons.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isProfileMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isProfileMode) "完成" else "任务配置",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isProfileMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isProfileMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 编辑任务按钮 - 具备高亮状态（）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleEditMode() },
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isEditMode) 2.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isEditMode) "完成" else "编辑任务",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isEditMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 新增任务按钮 - 仅在编辑模式下显示（）
        AnimatedVisibility(
            visible = isEditMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAddingTask() },
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAddingTask) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = if (isAddingTask) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else {
                        null
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isAddingTask) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "添加任务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isAddingTask) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (isAddingTask) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(nodes, key = { _, node -> node.entry + "@" + nodes.indexOf(node) }) { index, node ->
                key(node.entry + "@" + index) {
                    TaskNodeRow(
                        node = node,
                        taskName = allTasks.find { it.entry == node.entry }?.name ?: node.entry,
                        isSelected = selectedNodeId == node.entry,
                        isEditMode = isEditMode,
                        canMoveUp = index > 0,
                        canMoveDown = index < nodes.size - 1,
                        onEnabledChange = { enabled -> onNodeEnabledChange(node.entry, enabled) },
                        onSelected = { onNodeSelected(node.entry) },
                        onMoveUp = { onNodeMove(index, index - 1) },
                        onMoveDown = { onNodeMove(index, index + 1) },
                        onRemove = { onNodeRemove(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskNodeRow(
    node: ProfileManager.ProfileTask,
    taskName: String,
    isSelected: Boolean,
    isEditMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSelected: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        ) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelected() }
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = node.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = taskName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (isEditMode) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}