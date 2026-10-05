package com.xiaoran.nb.ui.glass

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xiaoran.nb.ui.model.MikasaData
import com.xiaoran.nb.ui.model.ResourceFile
import com.xiaoran.nb.ui.theme.GlassButton
import com.xiaoran.nb.ui.theme.GlassCard
import com.xiaoran.nb.ui.theme.LiquidGlassRoot

/**
 * 小染自动注入 —— 悬浮窗面板（Compose 液态玻璃）
 * - 顶部：标题 + 版本(小染pro) + 设置 + 关闭
 * - 导航：主页 / 功能 / 美化，可左右滑动（HorizontalPager）
 * - 主页：公告 + 设备名 + 输入的卡密
 * - 功能页：注入按钮 + 功能区文件（单选）+ 默认/pak 导入
 * - 美化页：注入按钮 + 美化区文件（单选）+ 默认/pak 导入
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassFloatingPanel(
    onClose: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenSettings: () -> Unit,
    onFloatToast: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE) }
    val cardKey = prefs.getString("card_key", "") ?: ""
    val deviceName = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }

    // 导航：主页=0 功能=1 美化=2，可左右滑动
    val pagerState = rememberPagerState(pageCount = { 3 })
    val current by remember { derivedStateOf { pagerState.currentPage } }
    val scope = rememberCoroutineScope()
    val tabNames = listOf("主页", "功能", "美化")

    LiquidGlassRoot {
    GlassCard(alpha = 0.45f, corner = 28) {
        Column(Modifier.padding(16.dp)) {
            // 顶部标题栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFB388FF).copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("染", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(MikasaData.APP_NAME, fontSize = 16.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Text(MikasaData.VERSION, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlassIconButton(Icons.Filled.Settings, onOpenSettings)
                GlassIconButton(Icons.Filled.Close, onClose)
            }
            Spacer(Modifier.height(12.dp))

            // 导航标签（可滑动，点击切页）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabNames.forEachIndexed { i, name ->
                    val on = current == i
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f))
                            .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 可左右滑动的内容页
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> HomeTab(announcement = MikasaData.announcement, deviceName = deviceName, cardKey = cardKey)
                    1 -> InjectTab(zone = "功能", files = MikasaData.functionFiles, onToast = onFloatToast)
                    2 -> InjectTab(zone = "美化", files = MikasaData.beautyFiles, onToast = onFloatToast)
                }
            }

            // 底部缩放 + 提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("← 左右滑动切换 →", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                // 缩放按钮（文字）
                Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.4f)).clickable { onZoomOut() }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.4f)).clickable { onZoomIn() }.padding(8.dp))
            }
        }
    }
    }
}

/** 玻璃小圆按钮 */
@Composable
private fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.4f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
    }
}

/** 主页：公告 + 设备名 + 卡密 */
@Composable
private fun HomeTab(announcement: String, deviceName: String, cardKey: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        GlassCard(alpha = 0.35f, corner = 20) {
            Column {
                Text("🔔 公告", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(announcement, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(alpha = 0.35f, corner = 18, modifier = Modifier.weight(1f)) {
                Column {
                    Text("设备", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(deviceName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            GlassCard(alpha = 0.35f, corner = 18, modifier = Modifier.weight(1f)) {
                Column {
                    Text("卡密", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (cardKey.isBlank()) "未输入" else cardKey,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (cardKey.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 注入页：注入按钮 + 该区文件单选 + 默认/pak 导入 */
@Composable
private fun InjectTab(zone: String, files: List<ResourceFile>, onToast: (String) -> Unit) {
    var selectedFile by remember { mutableIntStateOf(0) }
    var injectMode by remember { mutableIntStateOf(0) } // 0=默认导入 1=pak导入

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        GlassButton(modifier = Modifier.fillMaxWidth(), onClick = {
            onToast("开始注入：${files.getOrNull(selectedFile)?.name ?: "无"}（${MikasaData.injectModes[injectMode]}）")
        }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("注入 $zone 区", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${files.getOrNull(selectedFile)?.name ?: "未选择"} · ${MikasaData.injectModes[injectMode]}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 注入方式
        GlassCard(alpha = 0.3f, corner = 18) {
            Column {
                Text("注入方式", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MikasaData.injectModes.forEachIndexed { i, mode ->
                        val on = injectMode == i
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f))
                                .clickable { injectMode = i }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mode, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 文件单选
        GlassCard(alpha = 0.28f, corner = 18) {
            Column {
                Text("选择文件（单选）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                if (files.isEmpty()) {
                    Text("暂无文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    files.forEachIndexed { i, f ->
                        val on = selectedFile == i
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { selectedFile = i }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (on) "●" else "○",
                                    fontSize = 14.sp,
                                    color = if (on) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                Text("${f.zone} · ${f.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
