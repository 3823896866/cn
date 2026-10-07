package com.mikasa.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 液态玻璃风格常量。 */
object Glass {
    val shape = RoundedCornerShape(28.dp)
    val shapeSm = RoundedCornerShape(18.dp)
}

/**
 * 玻璃面板 Modifier：半透明渐变填充（顶部高光）+ 1dp 浅色描边 + 柔和悬浮阴影。
 * 深浅色自适应；纯叠加即可出"磨砂玻璃"效果（不依赖真·backdrop blur）。
 */
fun Modifier.glassPanel(
    dark: Boolean,
    alpha: Float = 0.60f,
    shape: RoundedCornerShape = Glass.shape,
    shadow: Dp = 8.dp
): Modifier = if (dark) {
    this
        .shadow(shadow, shape, color = Color(0x40000000))
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(Color(0x2EFFFFFF), Color(0x0FFFFFFF)),
                start = Offset.Zero
            )
        )
        .border(1.dp, Color(0x33FFFFFF), shape)
} else {
    this
        .shadow(shadow, shape, color = Color(0x1A000000))
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = (alpha + 0.25f).coerceAtMost(1f)),
                    Color.White.copy(alpha = (alpha - 0.15f).coerceAtLeast(0f))
                ),
                start = Offset.Zero
            )
        )
        .border(1.dp, Color.White.copy(alpha = 0.55f), shape)
}

/** 玻璃化导航栏/顶部容器：半透明 + 顶部细描边。 */
fun Modifier.glassBar(dark: Boolean): Modifier =
    if (dark) this
        .background(Color(0xFF141414).copy(alpha = 0.6f))
        .border(1.dp, Color(0x1FFFFFFF))
    else
        this
            .background(Color.White.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.7f))

/**
 * 鲜艳彩色"流动"玻璃底：基色渐变 + 若干彩色模糊光球（缓慢漂移 + 微脉动）。
 * 光球模糊半径由 blurRadius（"背景模糊程度"滑杆）控制；配色随主题 accent 变化。
 */
@Composable
fun LiquidGlassBackground(blurRadius: Dp) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary
    val baseA = if (dark) Color(0xFF0B0B16) else Color(0xFFEEF1FF)
    val baseB = if (dark) Color(0xFF14142A) else Color(0xFFF4ECFF)
    val orbs = listOf(
        Color(0x9922D3EE),
        Color(0x99A78BFA),
        Color(0x8AFFB300),
        accent.copy(alpha = 0.55f)
    )

    val t = rememberInfiniteTransition(label = "liquidOrbs")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phase"
    )
    val dx = 60.dp * (2f * phase)  // 0→120dp 往返
    val pulse = 0.85f + 0.3f * phase

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(baseA, baseB)))) {
        val xs = listOf((-60).dp, 220.dp, 40.dp, 180.dp)
        val ys = listOf(30.dp, 320.dp, 620.dp, 800.dp)
        orbs.forEachIndexed { i, c ->
            val w = (260.dp + (i * 40).dp) * pulse
            val baseX = xs[i]
            val baseY = ys[i]
            Box(
                Modifier
                    .size(w)
                    .offset(
                        x = baseX + dx * (if (i % 2 == 0) 1f else -1f),
                        y = baseY + 40.dp * (if (i % 3 == 0) phase else (1f - phase))
                    )
                    .blur(blurRadius.coerceAtLeast(24.dp))
                    .background(c, CircleShape)
            )
        }
        if (dark) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
    }
}
