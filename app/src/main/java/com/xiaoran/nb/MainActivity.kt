package com.xiaoran.nb

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xiaoran.nb.ui.launcher.MainScreen
import com.xiaoran.nb.ui.theme.LiquidGlassRoot
import com.xiaoran.nb.ui.theme.MikasaTheme

/**
 * 小染自动注入 启动器（Compose Material3）
 * - 8 页可左右滑动：主页 / 功能 / 美化 / 文件 / AI助手 / 音乐 / 权限 / 设置
 * - 字体风格（5 种）秒切换，状态提升到 Activity 以全局生效
 * - 状态栏透明（去掉顶部蓝色长条）
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 透明状态栏：去掉系统顶部蓝色长条
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val prefs = getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE)

        setContent {
            var fontStyle by remember { mutableIntStateOf(prefs.getInt("font_style", 0)) }
            MikasaTheme(fontStyle = fontStyle) {
                LiquidGlassRoot {
                    MainScreen(
                        fontStyle = fontStyle,
                        onFontStyleChange = { s ->
                            prefs.edit().putInt("font_style", s).apply()
                            fontStyle = s
                        }
                    )
                }
            }
        }
    }
}
