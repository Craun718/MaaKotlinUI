package com.maafw.naruto.ui.script

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask

/**
 * 任务列表 + 右侧详情布局喵～
 *  TaskListDetailLayout.kt。
 */
@Composable
fun TaskListDetailLayout(
    nodes: List<ProfileManager.ProfileTask>,
    allTasks: List<MaaTask>,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    profiles: List<String>,
    activeProfileId: String,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onNodeRemove: (Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
    onSwitchProfile: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onAddNode: (MaaTask) -> Unit,
    interfaceData: MaaInterface?,
    modifier: Modifier = Modifier,
    /** 右侧配置区包一层 Card（ wrapDetailInCard） */
    wrapDetailInCard: Boolean = true,
) {
    val configuration = LocalConfiguration.current
    // 一致：浮窗约 0.85 屏宽
    val floatMaxWidth = (configuration.screenWidthDp * 0.85f).dp

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentModifier = if (constraints.hasBoundedWidth) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .widthIn(max = floatMaxWidth)
                .fillMaxHeight()
                .fillMaxWidth()
        }

        Row(modifier = contentModifier) {
            TaskListPanel(
                nodes = nodes,
                allTasks = allTasks,
                selectedNodeId = selectedNodeId,
                isEditMode = isEditMode,
                isAddingTask = isAddingTask,
                isProfileMode = isProfileMode,
                onNodeEnabledChange = onNodeEnabledChange,
                onNodeSelected = onNodeSelected,
                onNodeMove = onNodeMove,
                onNodeRemove = onNodeRemove,
                onToggleEditMode = onToggleEditMode,
                onToggleAddingTask = onToggleAddingTask,
                onToggleProfileMode = onToggleProfileMode,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 80.dp, max = 104.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            DetailHost(
                wrapInCard = wrapDetailInCard,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (isProfileMode) {
                    // 任务配置管理界面（ 配置切换语义）喵
                    ProfileManagementPanel(
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        onSwitchProfile = onSwitchProfile,
                        onCreateProfile = onCreateProfile,
                        onDeleteProfile = onDeleteProfile,
                        onRenameProfile = onRenameProfile,
                        allTasks = allTasks,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    TaskConfigPanel(
                        selectedTask = allTasks.find { it.entry == selectedNodeId },
                        interfaceData = interfaceData,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHost(
    wrapInCard: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (wrapInCard) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp),
            ) {
                content()
            }
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}