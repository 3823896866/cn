package com.xiaoran.nb.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * MikasaUI 启动器配色
 */
object AppColors {
    val PageBg = Color(0xFFF8F8F8)
    val StatusGreen = Color(0xFFC7E9C0)
    val CardWhite = Color(0xFFFFFFFF)
    val BtnBlack = Color(0xFF111111)
    val SuccessGreen = Color(0xFF2E944B)
    val TextBlack = Color(0xFF111111)
    val TextGray = Color(0xFF8A8A8E)
}

/**
 * 市面上主流 AI 主题（外号 + 主题色），可秒切换
 */
enum class AiTheme(val key: String, val label: String, val accent: Color, val bg: Color) {
    Default("default", "经典淡绿", Color(0xFF2E944B), Color(0xFFC7E9C0)),
    DeepSeek("deepseek", "梁圣 · 深海蓝", Color(0xFF1E6FFF), Color(0xFFBFDBFF)),
    ChatGpt("chatgpt", "山姆 · 翠绿", Color(0xFF10A37F), Color(0xFFB7E8D8)),
    Gemini("gemini", "双子 · 蓝紫", Color(0xFF7C4DFF), Color(0xFFD9C9FF)),
    Doubao("doubao", "小豆 · 活力红", Color(0xFFE64C3C), Color(0xFFFFD6D0)),
    Kimi("kimi", "阿月 · 紫月", Color(0xFF9C27B0), Color(0xFFE8C8F0)),
    Claude("claude", "克劳德 · 暖橙", Color(0xFFD97706), Color(0xFFFCE3C8)),
    Qwen("qwen", "千问 · 青蓝", Color(0xFF00B8A9), Color(0xFFC9F5F0)),
    Wenxin("wenxin", "一言 · 中国蓝", Color(0xFF2456E6), Color(0xFFD5E0FF)),
    Zhipu("zhipu", "清言 · 晴空蓝", Color(0xFF3B82F6), Color(0xFFD6E6FF)),
    Spark("spark", "星火 · 蓝焰", Color(0xFF1F8FFF), Color(0xFFD2E7FF)),
    Hunyuan("hunyuan", "混元 · 霞紫", Color(0xFF6C5CE7), Color(0xFFE0DBFF)),
    Grok("grok", "格罗克 · 极简黑白", Color(0xFF37474F), Color(0xFFE0E0E0)),
    Copilot("copilot", "小飞 · 青绿", Color(0xFF00A67E), Color(0xFFC9F0E4)),
    Perplexity("perplexity", "思问 · 蔚蓝", Color(0xFF2080E8), Color(0xFFCFE4FF)),
    MetaAi("metaai", "元 · 靛紫", Color(0xFF5B5BD6), Color(0xFFD9D9FF)),
    Tiangong("tiangong", "天工 · 翡翠", Color(0xFF0FA77A), Color(0xFFC8F2E4)),
    XiaoMi("xiaomi", "小米 · 极简橙", Color(0xFFFF6A00), Color(0xFFFFE3CC)),
}

/** 根据 key 找主题 */
fun aiThemeByKey(key: String): AiTheme =
    AiTheme.entries.firstOrNull { it.key == key } ?: AiTheme.Default

private fun colorsFor(theme: AiTheme, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = theme.accent,
        onPrimary = Color.White,
        secondary = theme.bg,
        onSecondary = Color.Black,
        background = Color(0xFF111111),
        onBackground = Color.White,
        surface = Color(0xFF1E1E1E),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFB0B0B0),
    )
} else {
    lightColorScheme(
        primary = theme.accent,
        onPrimary = Color.White,
        secondary = theme.bg,
        onSecondary = Color(0xFF111111),
        background = Color(0xFFF8F8F8),
        onBackground = Color(0xFF111111),
        surface = Color.White,
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = AppColors.TextGray,
    )
}

/** M3 主题封装：AI 风格秒切换 + 跟随系统深色模式 */
@Composable
fun MikasaTheme(
    aiTheme: AiTheme = AiTheme.Default,
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontStyle: Int = 0,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorsFor(aiTheme, darkTheme),
        typography = AppFonts.typography(fontStyle),
        content = content
    )
}