package com.xiaoran.nb.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃统一入口（零第三方依赖的高仿降级版：毛玻璃近似 = 半透明 + 高光描边 + 投影）。
 * 保证 minSdk/compileSdk 兼容、可直接编译出包。
 *
 * 如需"折射级一模一样"，后续把工具链升到 backdrop-android 2.0.1 要求
 * （AGP 8.6+ / compileSdk 37 / Kotlin 2.x），再把 GlassCard 换成
 * com.kyant.backdrop.drawBackdrop + effects.lens/blur 即可（改动只在此文件）。
 */

/** 液态玻璃根（降级版：仅容器） */
@Composable
fun LiquidGlassRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

/** 液态玻璃卡片 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    alpha: Float = 0.35f,
    corner: Int = 24,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner.dp)
    Box(
        modifier = modifier
            .shadow((corner / 2).dp, shape, clip = false)
            .clip(shape)
            .background(Color.White.copy(alpha = alpha))
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
    ) { content() }
}

/** 液态玻璃按钮 */
@Composable
fun GlassButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    GlassCard(modifier = modifier, alpha = if (enabled) 0.4f else 0.2f, corner = 18) {
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** 玻璃底色层（可选） */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

/** 玻璃圆点指示 */
@Composable
fun GlassDot(modifier: Modifier = Modifier, active: Boolean = false) {
    Box(
        modifier
            .shadow(2.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = if (active) 0.9f else 0.4f), CircleShape)
    )
}
