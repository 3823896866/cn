package com.xiaoran.nb.ui.launcher

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoran.nb.ui.model.MikasaApi
import com.xiaoran.nb.ui.model.MikasaData
import com.xiaoran.nb.ui.theme.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 卡密验证页（对接后端 /api/cards/verify；连不上后端时回退为“非空即通过”）
 * - 输入卡密 + 「验证」→ 后端校验（或离线回退）
 * - 下方「解绑卡密」只能用一次（用过后置灰）
 * - 验证成功后播放启动图式进度条：链接服务器→链接成功→验证卡密→验证成功
 * - 全部走完回调 onSuccess(卡密)，此时才真正进入悬浮窗
 */
@Composable
fun VerifyCardPage(onSuccess: (cardKey: String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var cardKey by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(-1) } // -1=输入阶段
    var unbound by remember { mutableStateOf(prefs.getBoolean("card_unbound", false)) }

    // 进度动画：逐步骤前进
    LaunchedEffect(verifying) {
        if (!verifying) return@LaunchedEffect
        MikasaData.verifySteps.forEachIndexed { i, _ ->
            delay(900)
            step = i
        }
        // 全部完成 → 记录卡密 → 进入悬浮窗
        prefs.edit().putString("card_key", cardKey).apply()
        step = MikasaData.verifySteps.size // 结束标记
        delay(600)
        onSuccess(cardKey)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        // 标题
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFFB388FF).copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Text("染", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.height(14.dp))
        Text("小染自动注入", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        Text(MikasaData.VERSION, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(28.dp))

        if (step < 0) {
            // ── 输入阶段 ──
            GlassCard(alpha = 0.4f, corner = 22) {
                Column(Modifier.padding(18.dp)) {
                    Text("输入卡密", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("卡密是进入悬浮窗的通行证（后端校验；离线时任意非空内容即通过）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (errMsg.isNotEmpty()) {
                        Text("\u2716 $errMsg", fontSize = 12.sp, color = Color(0xFFE53935))
                    }
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = cardKey,
                        onValueChange = { cardKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.5f)),
                        placeholder = { Text("请输入卡密…", fontSize = 14.sp) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(Modifier.height(14.dp))
                    // 验证按钮
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                            .clickable(enabled = cardKey.isNotBlank() && !checking) {
                                errMsg = ""
                                scope.launch {
                                    checking = true
                                    val ok = withContext(Dispatchers.IO) { MikasaApi.verifyCard(cardKey) }
                                    checking = false
                                    if (ok) verifying = true
                                    else errMsg = "卡密验证失败，请重试"
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "验证并进入",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 解绑卡密（只能用一次）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (unbound) Color(0xFFE0E0E0).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.35f))
                            .clickable(enabled = !unbound) {
                                unbound = true
                                prefs.edit().putBoolean("card_unbound", true).apply()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (unbound) "解绑卡密（已使用，不可再用）" else "解绑卡密（仅可解绑一次）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (unbound) Color.Gray else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            // ── 进度阶段：启动图式进度条 ──
            GlassCard(alpha = 0.4f, corner = 22) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("✓", fontSize = 30.sp, color = Color(0xFF2E944B))
                    }
                    Spacer(Modifier.height(14.dp))
                    val showStep = step.coerceAtMost(MikasaData.verifySteps.size - 1)
                    Text(
                        if (step >= MikasaData.verifySteps.size) "验证成功" else MikasaData.verifySteps[showStep],
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    // 分步进度条
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        val progress = ((step + 1).toFloat() / (MikasaData.verifySteps.size + 1)).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("正在进入悬浮窗…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
