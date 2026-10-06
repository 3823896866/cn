package com.mikasa.ui.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * AI 专属启动动画选项（外号 + 主题色）
 */
data class LaunchAnimOption(val key: String, val label: String, val color: Color)

val launchAnimOptions = listOf(
    LaunchAnimOption("deepseek", "梁圣 · 深海波动", Color(0xFF1E6FFF)),
    LaunchAnimOption("chatgpt", "山姆 · 绿环旋转", Color(0xFF10A37F)),
    LaunchAnimOption("gemini", "双子 · 双环交错", Color(0xFF7C4DFF)),
    LaunchAnimOption("doubao", "小豆 · 红心脉动", Color(0xFFE64C3C)),
    LaunchAnimOption("kimi", "阿月 · 弯月星光", Color(0xFF9C27B0)),
    LaunchAnimOption("claude", "克劳德 · 星芒绽放", Color(0xFFD97706)),
    LaunchAnimOption("xiaomi", "小米 · 橙色脉冲", Color(0xFFFF6A00)),
)

fun launchAnimByKey(key: String): LaunchAnimOption =
    launchAnimOptions.firstOrNull { it.key == key } ?: launchAnimOptions[0]

/**
 * AI 启动动画弹窗
 * 流程：AI 专属动画循环（1.8s）→ 变绿打勾成功 → 回调 onFinished（此时才启动悬浮窗）
 * —— 动画没了 UI 才出来
 */
@Composable
fun AiLaunchDialog(animKey: String, onFinished: () -> Unit) {
    val anim = launchAnimByKey(animKey)

    // 阶段：0=动画播放 1=成功
    var phase by remember { mutableIntStateOf(0) }
    var checkProgress by remember { mutableFloatStateOf(0f) }
    var textVisible by remember { mutableStateOf(false) }

    // 通用循环动画
    val transition = rememberInfiniteTransition(label = "ai")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t"
    )

    // 时间线：动画 1.8s → 成功 → 对勾逐笔 → 关闭
    LaunchedEffect(Unit) {
        delay(1800)
        phase = 1
        checkProgress = 0f
        val steps = 14
        for (i in 1..steps) {
            delay(28)
            checkProgress = i / steps.toFloat()
        }
        textVisible = true
        delay(800)
        onFinished()
    }

    Dialog(
        onDismissRequest = { /* 播放期间不允许跳过 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6101010)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.size(220.dp)) {
                    val w = size.width
                    val h = size.height
                    val c = center

                    // ── 按动画类型绘制专属图形 ──
                    when (anim.key) {
                        // 梁圣：深海波动（正弦波 + 蓝环）
                        "deepseek" -> {
                            drawCircle(anim.color, radius = w * 0.32f, center = c,
                                style = Stroke(width = 3.dp.toPx()))
                            val wave = Path().apply {
                                moveTo(c.x - w * 0.32f, c.y + w * 0.08f)
                                repeat(24) { i ->
                                    val x = c.x - w * 0.32f + (w * 0.64f / 24) * i
                                    val y = c.y + w * 0.08f + sin((i / 24f + t) * PI * 2).toFloat() * w * 0.05f
                                    lineTo(x, y)
                                }
                            }
                            drawPath(wave, anim.color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                            drawCircle(Color.White, radius = w * 0.012f, center = Offset(c.x - w * 0.32f, c.y + w * 0.08f))
                        }
                        // 山姆：绿环 + 旋转光圈
                        "chatgpt" -> {
                            drawCircle(anim.color, radius = w * 0.30f, center = c,
                                style = Stroke(width = 3.dp.toPx()))
                            val arc = Path().apply {
                                addArc(
                                    androidx.compose.ui.geometry.Rect(
                                        c.x - w * 0.40f, c.y - w * 0.40f,
                                        c.x + w * 0.40f, c.y + w * 0.40f
                                    ),
                                    startAngleDegrees = t * 360f,
                                    sweepAngleDegrees = 110f
                                )
                            }
                            drawPath(arc, Color(0xCCFFFFFF), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                        }
                        // 双子：蓝紫双环交错旋转
                        "gemini" -> {
                            val rot = t * 360f
                            val r = w * 0.34f
                            val r2 = w * 0.22f
                            val a1 = rot * PI / 180
                            val a2 = -rot * PI / 180
                            drawCircle(
                                anim.color,
                                radius = r,
                                center = Offset(c.x + cos(a1).toFloat() * r * 0.18f, c.y + sin(a1).toFloat() * r * 0.18f),
                                style = Stroke(width = 4.dp.toPx())
                            )
                            drawCircle(
                                Color(0xFF00BCD4),
                                radius = r2,
                                center = Offset(c.x + cos(a2).toFloat() * r2 * 0.18f, c.y + sin(a2).toFloat() * r2 * 0.18f),
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                        // 小豆：红心脉动（跳动圆 + 圆角外框）
                        "doubao" -> {
                            val pulse = 1f + sin(t * PI * 2).toFloat() * 0.12f
                            drawCircle(anim.color, radius = w * 0.16f * pulse, center = c)
                            // 圆角方块外框
                            val box = Path().apply {
                                addRoundRect(
                                    androidx.compose.ui.geometry.RoundRect(
                                        c.x - w * 0.36f, c.y - w * 0.36f,
                                        c.x + w * 0.36f, c.y + w * 0.36f,
                                        androidx.compose.ui.geometry.CornerRadius(w * 0.10f)
                                    )
                                )
                            }
                            drawPath(box, anim.color, style = Stroke(width = 3.dp.toPx()))
                        }
                        // 阿月：弯月 + 星光闪烁
                        "kimi" -> {
                            val rot = (t * 30 - 15).toFloat()
                            drawArc(
                                anim.color,
                                startAngle = rot,
                                sweepAngle = 270f,
                                useCenter = false,
                                topLeft = Offset(c.x - w * 0.28f, c.y - w * 0.28f),
                                size = androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.56f),
                                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                            )
                            repeat(3) { i ->
                                val sa = t * 360f + i * 120f
                                val sx = c.x + cos(sa * PI / 180).toFloat() * w * 0.42f
                                val sy = c.y + sin(sa * PI / 180).toFloat() * w * 0.42f
                                drawCircle(Color(0xFFFFFFCC), radius = (3 + i).dp.toPx(), center = Offset(sx, sy))
                            }
                        }
                        // 克劳德：橙色星芒旋转绽放
                        "claude" -> {
                            val rot = t * 360f
                            repeat(8) { i ->
                                val ang = (i * 45 + rot) * PI / 180
                                val len = w * (0.26f + sin(t * PI * 2).toFloat() * 0.06f)
                                val x0 = c.x + cos(ang).toFloat() * w * 0.10f
                                val y0 = c.y + sin(ang).toFloat() * w * 0.10f
                                val x1 = c.x + cos(ang).toFloat() * len
                                val y1 = c.y + sin(ang).toFloat() * len
                                drawLine(anim.color, Offset(x0, y0), Offset(x1, y1),
                                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                            }
                            drawCircle(anim.color, radius = w * 0.06f, center = c)
                        }
                        // 小米：橙色脉冲（扩散圆环）
                        else -> {
                            val p = (t * 2) % 1f
                            drawCircle(anim.color.copy(alpha = (1f - p) * 0.7f),
                                radius = w * (0.10f + p * 0.28f), center = c,
                                style = Stroke(width = 3.dp.toPx()))
                            drawCircle(anim.color, radius = w * 0.10f, center = c)
                        }
                    }

                    // ── 成功后：绿色对勾逐笔 ──
                    if (phase == 1 && checkProgress > 0f) {
                        val tick = Path().apply {
                            moveTo(w * 0.36f, h * 0.52f)
                            lineTo(w * 0.47f, h * 0.63f)
                            lineTo(w * 0.65f, h * 0.42f)
                        }
                        val measure = androidx.compose.ui.graphics.PathMeasure()
                        measure.setPath(tick, false)
                        val totalLen = measure.length
                        val partial = Path()
                        measure.getSegment(0f, totalLen * checkProgress, partial, true)
                        drawPath(partial, Color(0xFF2E944B),
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                    }
                }

                Spacer(Modifier.height(26.dp))

                // 动画名 + 外号
                Text(
                    anim.label,
                    fontSize = 16.sp,
                    color = if (phase == 1) Color(0xFF2E944B) else Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (phase == 0) "AI 赋能中…" else "悬浮窗开启成功",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(6.dp))
                AnimatedVisibility(visible = textVisible, enter = fadeIn(tween(300))) {
                    Text(
                        "点悬浮球使用控制面板",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}