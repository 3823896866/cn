package com.xiaoran.nb.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/**
 * 液态玻璃（Kyant0/Backdrop，GitHub 原版开源）统一入口。
 *
 * 用法：
 * 1. 在根部用 [LiquidGlassRoot] 包住内容（它会创建并"记录"背景层）。
 * 2. 需要玻璃的控件用 [GlassCard] / [GlassButton]（自动折射 [LiquidGlassRoot] 里的背景）。
 *
 * 降级：
 * - 在没被 LiquidGlassRoot 包裹的地方，GlassCard 自动退回"毛玻璃近似"（半透明+高光+投影），保证 minSdk 兼容。
 * - 库的折射（lens）需 RuntimeShader（API33+）、模糊（blur）需 RenderEffect（API31+）；
 *   低于此自动 no-op，不会崩。
 */
val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/** 液态玻璃根：记录背景层，供内部 GlassCard/GlassButton 折射。 */
@Composable
fun LiquidGlassRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier.layerBackdrop(backdrop)) { content() }
    }
}

/** 液态玻璃卡片 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    alpha: Float = 0.35f,
    corner: Int = 24,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    val shape = RoundedCornerShape(corner.dp)
    val px = LocalDensity.current.density
    val glassModifier = modifier
        .shadow((corner / 2).dp, shape, clip = false)
        .then(
            if (backdrop != null) {
                // 折射级液态玻璃（原版 liquidGlass 折射 API）
                Modifier.drawBackdrop(backdrop, { shape }) {
                    blur((corner / 5).dp.value * px)
                    lens(
                        refractionHeight = 8.dp.value * px,
                        refractionAmount = 16.dp.value * px,
                        chromaticAberration = true
                    )
                }
            } else {
                // 降级：毛玻璃近似
                Modifier.background(Color.White.copy(alpha = alpha))
            }
        )
        .clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
    Box(glassModifier) { content() }
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

/** 玻璃底色（默认，视频/图片背景之上再叠一层玻璃感；可选） */
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
