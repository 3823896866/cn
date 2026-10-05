package com.xiaoran.nb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * 5 种字体风格（系统内置字族，无需外部 ttf）
 * 0 默认 / 1 iOS 精致 / 2 衬线 / 3 圆体 / 4 等宽
 */
object AppFonts {
    data class Option(val name: String, val family: FontFamily)

    val options = listOf(
        Option("默认", FontFamily.SansSerif),
        Option("iOS 精致", FontFamily.SansSerifMedium),
        Option("衬线", FontFamily.Serif),
        Option("圆体", FontFamily.Casual),
        Option("等宽", FontFamily.Monospace)
    )

    /** 按 styleIndex 生成一套应用该字体族的全局 Typography（M3 全槽位） */
    fun typography(styleIndex: Int): Typography {
        val f = options[styleIndex.coerceIn(0, options.size - 1)].family
        val b = Typography()
        fun c(ts: TextStyle) = ts.copy(fontFamily = f)
        return b.copy(
            displayLarge = c(b.displayLarge), displayMedium = c(b.displayMedium),
            displaySmall = c(b.displaySmall),
            headlineLarge = c(b.headlineLarge), headlineMedium = c(b.headlineMedium),
            headlineSmall = c(b.headlineSmall),
            titleLarge = c(b.titleLarge), titleMedium = c(b.titleMedium), titleSmall = c(b.titleSmall),
            bodyLarge = c(b.bodyLarge), bodyMedium = c(b.bodyMedium), bodySmall = c(b.bodySmall),
            labelLarge = c(b.labelLarge), labelMedium = c(b.labelMedium), labelSmall = c(b.labelSmall)
        )
    }
}
