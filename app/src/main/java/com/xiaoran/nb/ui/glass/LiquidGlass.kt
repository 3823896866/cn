package com.xiaoran.nb.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Liquid Glass）质感：零依赖实现（GitHub 开源思路：半透明 + 高光 + 描边 + 柔影）。
 * 只叠加视觉质感，不改动任何布局/位置。
 */

/** 液态玻璃背景画笔：底色自上而下「偏亮→中→略暗」的渐变，带透光感。 */
fun liquidGlassBrush(base: Color, alpha: Float = 0.6f): Brush =
    Brush.verticalGradient(
        colors = listOf(
            base.copy(alpha = (alpha + 0.3f).coerceAtMost(0.96f)),
            base.copy(alpha = alpha),
            base.copy(alpha = (alpha - 0.15f).coerceAtLeast(0.1f))
        ),
        startY = 0f,
        endY = 1f
    )

/** 液态玻璃修饰：柔影 + 圆角裁切 + 玻璃渐变 + 顶部高光描边。保持原尺寸与位置。 */
fun Modifier.liquidGlass(corner: Dp = 24.dp, base: Color = Color.White, alpha: Float = 0.6f): Modifier =
    this
        .shadow(corner / 5f, RoundedCornerShape(corner), clip = false)
        .clip(RoundedCornerShape(corner))
        .background(liquidGlassBrush(base, alpha))
        .border(
            1.dp,
            Brush.linearGradient(
                colors = listOf(Color(0xEEFFFFFF), Color(0x44FFFFFF), Color(0x99FFFFFF)),
                start = Offset(0f, 0f),
                end = Offset.Infinite
            ),
            RoundedCornerShape(corner)
        )
