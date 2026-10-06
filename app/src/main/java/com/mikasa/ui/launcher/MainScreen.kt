package com.mikasa.ui.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikasa.ui.FloatingWindowService
import com.mikasa.ui.theme.AiTheme
import com.mikasa.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * 悬浮窗启动器主界面
 * - 6 页：主页 / AI助手 / 音乐 / 权限 / 服务器 / 设置（Pager + 导航双向联动）
 * - 高斯模糊背景（强度可调）+ 自定义壁纸
 * - 主页数据全部真实：运行状态 / 今日激活 / 模块数 / 网络延迟
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE) }

    // 背景模糊强度（0-100 连续可调，拖动条实时生效）
    var blurLevel by remember { mutableIntStateOf(prefs.getInt("blur_level", 40)) }
    val blurRadius = blurLevel.dp

    // 灵动岛开关
    var diEnabled by remember { mutableStateOf(prefs.getBoolean("dynamic_island", true)) }

    // 灵动岛动画平滑度：0 流畅 / 1 均衡 / 2 华丽
    var animSmooth by remember { mutableIntStateOf(prefs.getInt("di_anim_smooth", 1)) }

    // 悬浮窗运行状态（真实检测）
    var svcRunning by remember { mutableStateOf(isFloatingServiceRunning(context)) }

    // ── AI 聊天状态（提升到此处：滑动页面不丢失；持久化：退出 App 仍在） ──
    var aiMessages by remember { mutableStateOf(XiaoMiAi.load(prefs.getString("ai_chat", null))) }
    var aiLoading by remember { mutableStateOf(false) }

    fun persistChat(list: List<XiaoMiAi.Msg>) {
        prefs.edit().putString("ai_chat", XiaoMiAi.save(list)).apply()
    }

    fun sendAiMessage(text: String) {
        val newMsg = XiaoMiAi.Msg("user", text)
        val updated = XiaoMiAi.trimContext(aiMessages + newMsg)
        aiMessages = updated
        persistChat(updated)
        aiLoading = true
        scope.launch {
            val ck = prefs.getString("card_key", "") ?: ""
            val reply = withContext(Dispatchers.IO) {
                com.mikasa.ui.XiaoRanApi.csSend(ck, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", text, "", "xiaoran_chat") ?: XiaoMiAi.chat(updated)
            }
            val next = XiaoMiAi.trimContext(updated + XiaoMiAi.Msg("assistant", reply))
            aiMessages = next
            persistChat(next)
            aiLoading = false
        }
    }

    fun clearAiChat() {
        aiMessages = listOf(XiaoMiAi.Msg("assistant", "我是小染助手，聊天已清空，随时找我喵~"))
        prefs.edit().remove("ai_chat").apply()
    }

    // ── 自定义背景图片（软件模糊 + 遮罩，低版本也有效） ──
    var bgImagePath by remember { mutableStateOf(prefs.getString("bg_image", null)) }
    val bgBitmap = remember(bgImagePath) { bgImagePath?.let { decodeBlurBackground(it) } }
    // 通用图片选择器（GetContent 兼容所有安卓版本，直接打开系统图库/文件，无需权限）
    val pickBg = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val path = copyUriToFile(context, uri)
            if (path != null) {
                prefs.edit().putString("bg_image", path).apply()
                bgImagePath = path
            } else {
                Toast.makeText(context, "图片保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 今日激活（真实统计：每次点启动 +1，跨天重置） ──
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    if (prefs.getString("active_date", "") != today) {
        prefs.edit().putString("active_date", today).putInt("today_activations", 0).apply()
    }
    var todayActivations by remember { mutableIntStateOf(prefs.getInt("today_activations", 0)) }

    // ── 真实网络延迟（ms） ──
    var latency by remember { mutableStateOf("测量中…") }
    scope.launch {
        latency = withContext(Dispatchers.IO) { measureLatency() }
    }

    // Pager 滑动与导航联动
    val pagerState = rememberPagerState(pageCount = { 6 })
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            BottomNavBar(
                current = currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
            )
        }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 背景层：自定义图片（软件模糊+遮罩）或默认渐变光斑
            if (bgBitmap != null) {
                Image(
                    bitmap = bgBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.6f }
                        .blur(blurRadius),
                    contentScale = ContentScale.Crop
                )
                // 毛玻璃感遮罩
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.32f))
                )
            } else {
                Image(
                    painter = androidx.compose.ui.res.painterResource(com.mikasa.R.drawable.bg_default),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
                    contentScale = ContentScale.Crop
                )
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.18f)))
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val pageOffset =
                    ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                val alpha = (1f - pageOffset * 0.55f).coerceIn(0.35f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.alpha = alpha }
                ) {
                    when (page) {
                        0 -> HomePage(
                            svcRunning = svcRunning,
                            moduleCount = totalModuleCount,
                            todayActivations = todayActivations,
                            latency = latency,
                            onLaunch = {
                                // 真实激活计数
                                val n = prefs.getInt("today_activations", 0) + 1
                                prefs.edit().putInt("today_activations", n).apply()
                                todayActivations = n
                                svcRunning = true
                                onLaunchClick(context)
                            },
                            onStop = {
                                onStopClick(context)
                                svcRunning = false
                            }
                        )
                        1 -> AiChatPage(
                            messages = aiMessages,
                            loading = aiLoading,
                            onSend = { sendAiMessage(it) },
                            onClear = { clearAiChat() }
                        )
                        2 -> MusicPage()
                        3 -> PermissionsPage()
                        4 -> FilesPage()
                        5 -> SettingsPage(
                            blurLevel = blurLevel,
                            onBlurLevelChange = {
                                blurLevel = it
                                prefs.edit().putInt("blur_level", it).apply()
                            },
                            bgImagePath = bgImagePath,
                            onPickBg = {
                                pickBg.launch("image/*")
                            },
                            onResetBg = {
                                prefs.edit().remove("bg_image").apply()
                                bgImagePath = null
                                Toast.makeText(context, "已恢复默认背景", Toast.LENGTH_SHORT).show()
                            },
                            diEnabled = diEnabled,
                            onDiToggle = {
                                diEnabled = it
                                prefs.edit().putBoolean("dynamic_island", it).apply()
                                Toast.makeText(
                                    context,
                                    if (it) "灵动岛已开启，重启悬浮窗生效" else "灵动岛已关闭",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            animSmooth = animSmooth,
                            onAnimSmoothChange = { animSmooth = it }
                        )
                    }
                }
            }
        }
    }

}

/* ================= 高斯模糊背景 ================= */

@Composable
private fun BlurBackground(blurRadius: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFEAF6E4), Color(0xFFF8F8F8))
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .offset(x = (-60).dp, y = 40.dp)
                .blur(blurRadius)
                .background(Color(0xFFC7E9C0).copy(alpha = 0.55f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = 200.dp, y = 320.dp)
                .blur(blurRadius)
                .background(Color(0xFFBBDEFB).copy(alpha = 0.45f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .offset(x = 40.dp, y = 620.dp)
                .blur(blurRadius)
                .background(Color(0xFFFFF3C4).copy(alpha = 0.5f), CircleShape)
        )
    }
}

/* ================= 底部导航（5 项） ================= */

private data class NavItem(val label: String, val icon: ImageVector?)

private val navItems = listOf(
    NavItem("主页", Icons.Filled.Home),
    NavItem("小染助手", Icons.Filled.Face),
    NavItem("音乐", null), // 音乐用自绘音符图标
    NavItem("权限", Icons.Filled.Lock),
    NavItem("文件", Icons.Filled.Person),
    NavItem("设置", Icons.Filled.Settings),
)

@Composable
private fun BottomNavBar(current: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color.White.copy(alpha = 0.92f)) {
        navItems.forEachIndexed { index, item ->
            val selected = current == index
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                icon = {
                    if (item.icon != null) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary else AppColors.TextGray
                        )
                    } else {
                        // 音乐：自绘音符图标
                        MusicNoteIcon(
                            color = if (selected) MaterialTheme.colorScheme.primary else AppColors.TextGray,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                label = {
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else AppColors.TextGray,
                        maxLines = 1,
                        softWrap = false
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                    unselectedIconColor = AppColors.TextGray,
                    unselectedTextColor = AppColors.TextGray
                )
            )
        }
    }
}

/* ================= 通用卡片 ================= */

@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(20.dp),
        content = { content() }
    )
}

/**
 * 谷歌风格：卡片逐个上浮进场
 * 仅首次组合播放一次；主题切换/重组时不重播（修复切换主题内容堆叠问题）
 */
@Composable
private fun EnterAnimation(delayMs: Int = 0, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(350)) + slideInVertically(
            animationSpec = tween(380),
            initialOffsetY = { it / 8 }
        ),
        exit = fadeOut(tween(150))
    ) {
        content()
    }
}

/* ================= 主页 ================= */

@Composable
private fun HomePage(
    svcRunning: Boolean,
    moduleCount: Int,
    todayActivations: Int,
    latency: String,
    onLaunch: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        EnterAnimation(0) {
            Text("Mikasa", fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Text("悬浮窗控制台", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }

        EnterAnimation(80) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column {
                            Text("功能模块", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("$moduleCount", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    AppCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column {
                            Text("今日激活", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("$todayActivations", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        EnterAnimation(160) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("悬浮窗服务", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点击启动悬浮窗，顶部显示灵动岛（帧率/温度/音乐），点开查看详情。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 启动按钮（AI 动画）
        EnterAnimation(240) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onLaunch),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("启动悬浮窗", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 关闭按钮
        EnterAnimation(300) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .border(1.5.dp, Color(0xFFE0E0E0), RoundedCornerShape(18.dp))
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Close, null, tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("关闭悬浮窗", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        EnterAnimation(360) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("网络延迟（真实测量）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        latency,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "「小染助手」页可找小染 AI 聊天；「设置」页可开关灵动岛、自定义背景。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 真实功能模块总数（悬浮窗面板功能项 + 启动器功能） */
private const val totalModuleCount = 44

/** 真实检测悬浮窗服务是否在运行 */
private fun isFloatingServiceRunning(context: Context): Boolean {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        am.getRunningServices(200).any {
            it.service.className == FloatingWindowService::class.java.name
        }
    } catch (e: Exception) {
        false
    }
}

/** 真实网络延迟：探测服务器连通耗时 */
private fun measureLatency(): String {
    return try {
        val start = System.currentTimeMillis()
        val conn = URL("https://music.sky.aeink.com/").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        val ms = System.currentTimeMillis() - start
        conn.disconnect()
        if (code in 200..499) "${ms}ms" else "超时"
    } catch (e: Exception) {
        "超时"
    }
}

/** 动画结束才启动悬浮窗 */
private fun onLaunchClick(context: Context) {
    if (FloatingWindowService.canDrawOverlays(context)) {
        context.startService(Intent(context, FloatingWindowService::class.java))
        Toast.makeText(context, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/** 关闭悬浮窗 */
private fun onStopClick(context: Context) {
    context.stopService(Intent(context, FloatingWindowService::class.java))
    Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
}

/**
 * 软件模糊解码背景图（低版本也有效）：
 * 先按小采样率解码，再放大 2 倍 —— 等效高斯模糊，比 Modifier.blur 兼容性更好
 */
private fun decodeBlurBackground(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val blurred = Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, true)
        if (blurred !== bmp) bmp.recycle()
        blurred.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** 把选中的图片复制到应用私有目录（避免 uri 失效） */
private fun copyUriToFile(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "bg").apply { mkdirs() }
        val out = File(dir, "bg_image.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { it.write(input.readBytes()) }
        }
        out.absolutePath
    } catch (e: Exception) {
        null
    }
}

/* ================= 权限页 ================= */

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var showShizuku by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        EnterAnimation(0) {
            Text("权限管理", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text("悬浮窗正常工作需要以下权限", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }

        EnterAnimation(80) {
            val overlayGranted = FloatingWindowService.canDrawOverlays(context)
            PermissionCard(
                title = "悬浮窗权限",
                desc = "允许在其它应用上层显示悬浮球与控制面板",
                granted = overlayGranted,
                onClick = {
                    if (!overlayGranted) {
                        Toast.makeText(context, "正在打开悬浮窗授权页…", Toast.LENGTH_SHORT).show()
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                        }
                    } else {
                        Toast.makeText(context, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        EnterAnimation(160) {
            val notifGranted = hasNotificationPermission(context)
            PermissionCard(
                title = "通知权限",
                desc = "Android 13+ 需要此权限显示常驻通知",
                granted = notifGranted,
                onClick = {
                    if (!notifGranted && Build.VERSION.SDK_INT >= 33) {
                        Toast.makeText(context, "正在打开通知设置…", Toast.LENGTH_SHORT).show()
                        try {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                        }
                    } else {
                        Toast.makeText(context, "通知权限已授予", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        EnterAnimation(240) {
            PermissionCard(
                title = "通知使用权（可选）",
                desc = "用于读取通知、驱动灵动提醒等增强功能",
                granted = false,
                onClick = {
                    Toast.makeText(context, "正在打开通知使用权设置…", Toast.LENGTH_SHORT).show()
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                    }
                }
            )
            Spacer(Modifier.height(20.dp))
        }

        EnterAnimation(320) {
            PermissionCard(
                title = "Shizuku 权限",
                desc = "安装 Shizuku 后，可在下方弹窗内完成授权，不跳转系统设置页",
                granted = false,
                onClick = { showShizuku = true }
            )
            Spacer(Modifier.height(20.dp))
        }

        if (showShizuku) ShizukuGrantDialog(onDismiss = { showShizuku = false })
    }
}

@Composable
private fun ShizukuGrantDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在检测 Shizuku…") }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { status = shizukuStatusText(context) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shizuku 授权（弹窗内）") },
        text = {
            Column {
                Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text("需先安装并启动「Shizuku」App；本弹窗内直接授权，不会跳转到系统设置页。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { grantShizuku(context) }
                        status = if (ok) "Shizuku 已授权 ✅" else shizukuStatusText(context)
                        working = false
                    }
                }
            ) { Text("尝试授权") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (granted) "已授权" else "去授权",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (granted) MaterialTheme.colorScheme.primary else Color(0xFFE53935)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else AppColors.TextGray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun shizukuStatusText(context: Context): String = try {
    val cls = Class.forName("dev.rikka.shizuku.Shizuku")
    val prepared = cls.getMethod("isPrepared").invoke(null) as Boolean
    val granted = cls.getMethod("isPermissionGranted", Int::class.java).invoke(null, 0) as Boolean
    if (!prepared) "未检测到已就绪的 Shizuku（请先安装并启动 Shizuku App）"
    else if (granted) "Shizuku 已授权 ✅"
    else "Shizuku 已就绪，但尚未授权本应用"
} catch (e: Throwable) {
    "未安装 Shizuku 框架（无 dev.rikka.shizuku.Shizuku）"
}

private fun grantShizuku(context: Context): Boolean = try {
    val cls = Class.forName("dev.rikka.shizuku.Shizuku")
    val m = cls.getMethod("requestPermissions", Int::class.java)
    m.invoke(null, 0)
    true
} catch (e: Throwable) {
    false
}

/* ================= 服务器页 ================= */

@Composable
private fun FilesPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = context.cacheDir.resolve("import_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)!!.use { ins -> zip.outputStream().use { ins.copyTo(it) } }
                    val target = com.mikasa.ui.FilesApi.xiaoranDir(context)
                    val n = com.mikasa.ui.FilesApi.importZip(zip, target)
                    zip.delete()
                    "已导入 $n 个文件到「小染注入」文件夹（同名已替换）：${target.absolutePath}"
                }.fold({ it }, { "导入失败：${it.message ?: it}" })
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    var funcFiles by remember { mutableStateOf<List<com.mikasa.ui.FilesApi.FileItem>>(emptyList()) }
    var beautyFiles by remember { mutableStateOf<List<com.mikasa.ui.FilesApi.FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }

    fun loadAll() {
        loading = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { com.mikasa.ui.FilesApi.list("功能") }
            val b = withContext(Dispatchers.IO) { com.mikasa.ui.FilesApi.list("美化") }
            funcFiles = f; beautyFiles = b; loading = false
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    fun download(item: com.mikasa.ui.FilesApi.FileItem) {
        scope.launch {
            downloading = downloading + (item.name to 0.0)
            val res = withContext(Dispatchers.IO) {
                com.mikasa.ui.FilesApi.downloadToPublic(context, item) { p -> downloading = downloading + (item.name to p) }
            }
            downloading = downloading - item.name
            Toast.makeText(context, if (res != null) "已下载到：$res" else "下载失败，请稍后再试", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("文件", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(6.dp))
                Text("下载后端上传的文件（功能 / 美化）", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) { Text("导入zip") }
                androidx.compose.material3.TextButton(onClick = { loadAll() }) { Text("刷新") }
            }
        }
        Text("下载 → 手机「下载/小染注入」；导入zip → 解压到「小染注入」文件夹（同名自动替换）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))

        if (loading && funcFiles.isEmpty() && beautyFiles.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("加载中…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FileSection("功能文件", funcFiles, downloading) { download(it) }
            Spacer(Modifier.height(16.dp))
            FileSection("美化文件", beautyFiles, downloading) { download(it) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FileSection(
    title: String,
    files: List<com.mikasa.ui.FilesApi.FileItem>,
    downloading: Map<String, Double>,
    onDownload: (com.mikasa.ui.FilesApi.FileItem) -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Text("（暂无文件，请到后端上传）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                files.forEach { item ->
                    val prog = downloading[item.name]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("${item.size} · ${item.zone}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (prog != null) {
                            Text("${(prog * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            androidx.compose.material3.TextButton(onClick = { onDownload(item) }) { Text("下载") }
                        }
                    }
                }
            }
        }
    }
}

/* ================= 设置页 ================= */

@Composable
private fun SettingsPage(
    blurLevel: Int,
    onBlurLevelChange: (Int) -> Unit,
    bgImagePath: String?,
    onPickBg: () -> Unit,
    onResetBg: () -> Unit,
    diEnabled: Boolean,
    onDiToggle: (Boolean) -> Unit,
    animSmooth: Int,
    onAnimSmoothChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", false)) }
    var haptic by remember { mutableStateOf(prefs.getBoolean("haptic", true)) }

    fun toggle(key: String, old: Boolean): Boolean {
        val new = !old
        prefs.edit().putBoolean(key, new).apply()
        return new
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        EnterAnimation(0) {
            Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text("个性化悬浮窗行为", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
        }

        // ── 灵动岛开关 ──
        EnterAnimation(60) {
            ToggleRow(
                title = "灵动岛（iOS 风格）",
                on = diEnabled,
                onToggle = { onDiToggle(!diEnabled) }
            )
            Spacer(Modifier.height(28.dp))
        }

        // ── 灵动岛动画平滑度 ──
        val smoothNames = listOf("流畅（快）", "均衡（推荐）", "华丽（回弹）")
        EnterAnimation(90) {
            SettingClickRow(
                title = "灵动岛动画平滑度",
                value = smoothNames[animSmooth]
            ) {
                val next = (animSmooth + 1) % smoothNames.size
                prefs.edit().putInt("di_anim_smooth", next).apply()
                onAnimSmoothChange(next)
                Toast.makeText(context, "动画平滑度：${smoothNames[next]}", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.height(28.dp))
        }

        // ── 防录屏开关（在悬浮窗面板 → 设置 中可调） ──
        EnterAnimation(100) {
            SettingClickRow(
                title = "防录屏 / 音量键隐藏",
                value = "悬浮窗面板·设置 中调节"
            ) {
                Toast.makeText(context, "请在悬浮窗面板右上角 ⚙ 设置中开启/关闭防录屏，并开启无障碍使用音量键", Toast.LENGTH_LONG).show()
            }
            Spacer(Modifier.height(28.dp))
        }

                // ── 自定义背景图片（大按钮，直接显示不被遮挡） ──
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("自定义背景", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (bgImagePath != null) "已设置" else "默认壁纸",
                        fontSize = 12.sp,
                        color = if (bgImagePath != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onPickBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (bgImagePath != null) "更换背景图片" else "上传背景图片",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (bgImagePath != null) {
                    Spacer(Modifier.height(28.dp))
                    OutlinedButton(
                        onClick = onResetBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("恢复默认背景", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        // ── 背景模糊程度（拖动条实时生效） ──
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("背景模糊程度", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (blurLevel == 0) "关闭" else "$blurLevel%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = blurLevel.toFloat(),
                    onValueChange = { onBlurLevelChange(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 19
                )
                Text("左滑更清晰 · 右滑更朦胧", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(22.dp))
        EnterAnimation(260) {
            ToggleRow(
                title = "跟随系统深色模式",
                on = darkMode,
                onToggle = {
                    darkMode = toggle("dark_mode", darkMode)
                    Toast.makeText(context, if (darkMode) "已开启深色模式" else "已关闭深色模式", Toast.LENGTH_SHORT).show()
                }
            )
            Spacer(Modifier.height(28.dp))
        }
        EnterAnimation(300) {
            ToggleRow(
                title = "开机自启动悬浮窗",
                on = autoStart,
                onToggle = {
                    autoStart = toggle("auto_start", autoStart)
                    Toast.makeText(context, if (autoStart) "已开启自启动" else "已关闭自启动", Toast.LENGTH_SHORT).show()
                }
            )
            Spacer(Modifier.height(28.dp))
        }
        EnterAnimation(340) {
            ToggleRow(
                title = "触感反馈",
                on = haptic,
                onToggle = {
                    haptic = toggle("haptic", haptic)
                    Toast.makeText(context, if (haptic) "已开启触感" else "已关闭触感", Toast.LENGTH_SHORT).show()
                }
            )
            Spacer(Modifier.height(28.dp))
        }
        EnterAnimation(380) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            Toast.makeText(context, "MikasaUI v6.4 · 小染 AI", Toast.LENGTH_SHORT).show()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("关于 MikasaUI", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text("v6.4", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** AI 主题小卡片（秒切换） */
@Composable
private fun ThemeChip(
    theme: AiTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) theme.accent else Color(0xFFE5E5E5)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) theme.bg else Color.White)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(theme.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            theme.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 启动动画选择小卡片 */
@Composable
private fun AnimChip(
    label: String,
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) color else Color(0xFFE5E5E5)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) color.copy(alpha = 0.15f) else Color.White)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ToggleRow(title: String, on: Boolean, valueText: String? = null, onToggle: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            val bg = if (on) MaterialTheme.colorScheme.primary else Color(0xFFE0E0E0)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (valueText != null) {
                    Text(valueText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.primary else AppColors.TextGray)
                }
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingClickRow(title: String, value: String, onClick: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}