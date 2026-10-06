package com.mikasa

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mikasa.ui.launcher.MainScreen
import com.mikasa.ui.theme.MikasaTheme
import com.mikasa.ui.theme.aiThemeByKey

/**
 * MikasaUI 悬浮窗启动器（Compose Material3）
 * - 5 页 Pager：主页 / AI助手 / 权限 / 服务器 / 设置
 * - AI 风格主题秒切换（状态提升到 Activity）
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE)

        setContent {
            MikasaTheme() {
                MainScreen()
            }
        }
    }
}