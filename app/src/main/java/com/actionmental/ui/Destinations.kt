package com.actionmental.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val title: String,
    val technical: String,
    val icon: ImageVector,
    val inBottomBar: Boolean,
) {
    DASHBOARD("状态中心", "Dashboard", Icons.Filled.Dashboard, true),
    SHORTCUTS("快捷键", "Shortcuts", Icons.Filled.Keyboard, true),
    REMAP("键位映射", "Key remap", Icons.Filled.SwapHoriz, true),
    MONITOR("按键检测", "Monitor", Icons.Filled.Speed, true),
    ROTATION("屏幕方向", "Rotation", Icons.Filled.ScreenRotation, true),
    PRIVILEGE("Shizuku 与磁贴", "Privilege", Icons.Filled.Security, false),
    LOGS("运行日志", "Logs", Icons.Filled.Article, false),
    SETTINGS("设置", "Settings", Icons.Filled.Settings, false),
}
