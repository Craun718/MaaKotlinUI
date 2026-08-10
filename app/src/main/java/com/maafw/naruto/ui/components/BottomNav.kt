package com.maafw.naruto.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.maafw.naruto.R

/**
 * 底部导航栏喵～
 *  的三栏结构：主页 / 定时任务 / 设置喵。
 * 三个图标全部使用液态玻璃游戏图标包喵。
 */
enum class MaaScreen(val title: String, val icon: @Composable () -> Unit) {
    HOME("主页", { Icon(painterResource(R.drawable.ic_home), contentDescription = null) }),
    SCRIPT("脚本", { Icon(painterResource(R.drawable.ic_script), contentDescription = null) }),
    SCHEDULE("定时任务", { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) }),
    SETTINGS("设置", { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) })
}

@Composable
fun MaaBottomBar(
    current: MaaScreen,
    onSelect: (MaaScreen) -> Unit
) {
    NavigationBar {
        MaaScreen.values().forEach { screen ->
            NavigationBarItem(
                icon = { screen.icon() },
                label = { Text(screen.title) },
                selected = current == screen,
                onClick = { onSelect(screen) }
            )
        }
    }
}