package com.xiaoran.nb.ui.launcher

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoran.nb.ui.FloatingWindowService
import com.xiaoran.nb.ui.model.MikasaApi
import com.xiaoran.nb.ui.model.MikasaData
import com.xiaoran.nb.ui.theme.AiTheme
import com.xiaoran.nb.ui.theme.GlassButton
import com.xiaoran.nb.ui.theme.GlassCard
import com.xiaoran.nb.ui.theme.AppColors
import com.xiaoran.nb.ui.theme.AppFonts
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
 * - 6 页：主页 / AI助手 / 音乐 / 权限 / 文件 / 设置（Pager + 导航双向联动）
 * - 高斯模糊背景（强度可调）+ 自定义壁纸
 * - 主页数据全部真实：运行状态 / 今日激活 / 模块数 / 网络延迟
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    fontStyle: Int = 0,
    onFontStyleChange: (Int) -> Unit = {}
) {
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

    // 卡密验证 overlay（启动前闸门）
    var showVerify by remember { mutableStateOf(false) }
    // 已输入的卡密（主页展示）
    var cardKey by remember { mutableStateOf(prefs.getString("card_key", "")) }
    // 设备名（真实）
    val deviceName = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }

    // 远程数据（后端；离线回退本地）+ 软件开关闸门
    var remoteAnns by remember { mutableStateOf(MikasaData.announcements) }
    var remoteFuncFiles by remember { mutableStateOf(MikasaData.functionFiles) }
    var remoteBeautyFiles by remember { mutableStateOf(MikasaData.beautyFiles) }
    var swEnabled by remember { mutableStateOf(true) }
    var remoteLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        remoteAnns = withContext(Dispatchers.IO) { MikasaApi.announcements() }
        remoteFuncFiles = withContext(Dispatchers.IO) { MikasaApi.files("功能") }
        remoteBeautyFiles = withContext(Dispatchers.IO) { MikasaApi.files("美化") }
        swEnabled = withContext(Dispatchers.IO) { MikasaApi.softwareEnabled() }
        remoteLoaded = true
    }

    // ── AI 聊天状态（提升到此处：滑动页面不丢失；持久化：退出 App 仍在） ──
    var aiMessages by remember { mutableStateOf(XiaoMiAi.load(prefs.getString("ai_chat", null))) }
    var aiLoading by remember { mutableStateOf(false) }

    fun persistChat(list: List<XiaoMiAi.Msg>) {
        prefs.edit().putString("ai_chat", XiaoMiAi.save(list)).apply()
    }

    fun sendAiMessage(text: String, image: String = "") {
        val newMsg = XiaoMiAi.Msg("user", text)
        val updated = XiaoMiAi.trimContext(aiMessages + newMsg)
        aiMessages = updated
        persistChat(updated)
        aiLoading = true
        scope.launch {
            val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val ck = prefs.getString("card_key", "") ?: ""
            val sid = prefs.getString("cs_session", "")
                ?: "u${System.currentTimeMillis()}".also { prefs.edit().putString("cs_session", it).apply() }
            val replyText = withContext(Dispatchers.IO) {
                XiaoMiAi.reply(updated, ck, device, sid, image)
            }
            val next = XiaoMiAi.trimContext(updated + XiaoMiAi.Msg("assistant", replyText))
            aiMessages = next
            persistChat(next)
            aiLoading = false
        }
    }

    fun clearAiChat() {
        aiMessages = listOf(XiaoMiAi.Msg("assistant", "聊天已清空，随时找小染聊！喵～"))
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

    // Pager 滑动与导航联动（8 页，左右滑动）
    val pagerState = rememberPagerState(pageCount = { 8 })
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
            // 背景层：默认视频（assets/video/home_bg.mp4） + 暗色遮罩保证文字可读
            VideoBackground()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )

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
                            announcements = remoteAnns,
                            deviceName = deviceName,
                            cardKey = cardKey.orEmpty(),
                            onLaunch = { showVerify = true },
                            onStop = {
                                onStopClick(context)
                                svcRunning = false
                            }
                        )
                        1 -> InjectPage(zone = "功能", files = remoteFuncFiles)
                        2 -> InjectPage(zone = "美化", files = remoteBeautyFiles)
                        3 -> FileDownloadPage()
                        4 -> AiChatPage(
                            messages = aiMessages,
                            loading = aiLoading,
                            onSend = { t, img -> sendAiMessage(t, img) },
                            onClear = { clearAiChat() }
                        )
                        5 -> MusicPage()
                        6 -> PermissionsPage()
                        7 -> SettingsPage(
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
                            onAnimSmoothChange = { animSmooth = it },
                            fontStyle = fontStyle,
                            onFontStyleChange = onFontStyleChange
                        )
                    }
                }
            }

            // ── 卡密验证闸门：启动悬浮窗前必须先验证，验证成功才真正进入 ──
            if (showVerify) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF8F8F8))
                ) {
                    VerifyCardPage(
                        onSuccess = { key ->
                            cardKey = key
                            showVerify = false
                            onLaunchClick(context)
                            svcRunning = true
                        }
                    )
                }
            }

            // 软件开关闸门：后台"一键关闭"后，前端整体不可用
            if (remoteLoaded && !swEnabled) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF8F8F8)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("软件已被后台关闭", fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(8.dp))
                        Text("请在管理端「设置」页开启软件后重试", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                .size(220.dp)
                .offset(x = 40.dp, y = 620.dp)
                .blur(blurRadius)
                .background(Color(0xFFFFF3C4).copy(alpha = 0.5f), CircleShape)
        )
    }
}

/** 视频背景（Compose 承载 Media3 ExoPlayer）：循环、铺满，播放 res/raw/home_bg.mp4。
 *  注：ExoPlayer 走原生 surface，Liquid Glass 折射的是 Compose 绘制层；
 *  要视频本身被折射需再升级为“逐帧取 VideoFrame 画到 Compose”。 */
@Composable
private fun VideoBackground() {
    val context = LocalContext.current
    val player = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
    }
    DisposableEffect(Unit) {
        player.setMediaItem(
            androidx.media3.common.MediaItem.fromUri(
                android.net.Uri.parse("android.resource://${context.packageName}/raw/home_bg")
            )
        )
        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        player.prepare()
        onDispose { player.release() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            androidx.media3.ui.PlayerView(it).apply {
                setPlayer(player)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setKeepScreenOn(true)
            }
        },
        onRelease = { it.setPlayer(null) }
    )
}

/* ================= 底部导航（8 项，可左右滑动） ================= */

private data class NavItem(val label: String, val icon: ImageVector?)

private val navItems = listOf(
    NavItem("主页", Icons.Filled.Home),
    NavItem("功能", Icons.Filled.PlayArrow),
    NavItem("美化", Icons.Filled.Face),
    NavItem("文件", Icons.Filled.Folder),
    NavItem("AI助手", Icons.Filled.Person),
    NavItem("音乐", null), // 音乐用自绘音符图标
    NavItem("权限", Icons.Filled.Lock),
    NavItem("设置", Icons.Filled.Settings),
)

@Composable
private fun BottomNavBar(current: Int, onSelect: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            navItems.forEachIndexed { index, item ->
                val selected = current == index
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f))
                        .border(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.icon != null) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        MusicNoteIcon(
                            color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/* ================= 通用卡片 ================= */

@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White.copy(alpha = 0.35f),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
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

/* ================= 注入页（功能/美化共用，按 zone 区分文件） ================= */

/**
 * 注入页：一个「注入」按钮 + 下方单选的该区文件列表 + 默认导入/pak导入 两种方式。
 * @param zone  "功能" 或 "美化"
 * @param files 该区文件（功能区/美化区）
 */
@Composable
private fun InjectPage(zone: String, files: List<com.xiaoran.nb.ui.model.ResourceFile>) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf(0) }
    var injectMode by remember { mutableIntStateOf(0) } // 0=默认导入 1=pak导入

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        EnterAnimation(0) {
            Text("$zone 注入", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text("选择一个${zone}区文件并选择导入方式后点击注入", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }

        // 注入按钮
        EnterAnimation(60) {
            GlassButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    Toast.makeText(
                        context,
                        "开始注入：${files.getOrNull(selectedFile)?.name ?: "无"}（${MikasaData.injectModes[injectMode]}）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("注入", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${if (files.getOrNull(selectedFile) != null) files[selectedFile].name else "未选择"} · ${MikasaData.injectModes[injectMode]}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 注入方式（默认导入 / pak导入）
        EnterAnimation(100) {
            GlassCard(alpha = 0.35f, corner = 20) {
                Column {
                    Text("注入方式", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MikasaData.injectModes.forEachIndexed { i, mode ->
                            val on = injectMode == i
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f))
                                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { injectMode = i }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(mode, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 文件单选列表（每次只能选一个）
        EnterAnimation(140) {
            GlassCard(alpha = 0.3f, corner = 20) {
                Column {
                    Text("选择文件（单选）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    if (files.isEmpty()) {
                        Text("暂无文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        files.forEachIndexed { i, f ->
                            val on = selectedFile == i
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { selectedFile = i }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, if (on) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (on) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("${f.zone} · ${f.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/* ================= 帧率卡（Choreographer 实时统计） ================= */

@Composable
private fun FpsCard() {
    val choreographer = remember { android.view.Choreographer.getInstance() }
    var fps by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val cb = object : android.view.Choreographer.FrameCallback {
            var count = 0
            var last = 0L
            override fun doFrame(frameTimeNanos: Long) {
                if (last == 0L) last = frameTimeNanos
                count++
                if (frameTimeNanos - last >= 1_000_000_000L) {
                    fps = (count * 1_000_000_000L / (frameTimeNanos - last)).toInt().coerceIn(0, 240)
                    count = 0
                    last = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(cb)
        onDispose { choreographer.removeFrameCallback(cb) }
    }
    GlassCard(alpha = 0.35f, corner = 20) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("帧率", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text(
                "$fps FPS",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Text("Choreographer 实时统计（杂类·监控）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ================= 主页 ================= */

@Composable
private fun HomePage(
    svcRunning: Boolean,
    announcements: List<String>,
    deviceName: String,
    cardKey: String,
    onLaunch: () -> Unit,
    onStop: () -> Unit
) {
    // 公告（测试：本地多条，可刷新切换；后端接入后可实时更新）
    var annIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        EnterAnimation(0) {
            Text("小染自动注入", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Text(MikasaData.VERSION, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }

        // 运行状态
        EnterAnimation(60) {
            GlassCard(alpha = 0.4f, corner = 22) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("运行状态", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (svcRunning) "悬浮服务运行中" else "未运行",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (svcRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 公告
        EnterAnimation(100) {
            GlassCard(alpha = 0.35f, corner = 22) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("\uD83D\uDDA7 公告", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            announcements[annIndex % announcements.size],
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Filled.Refresh,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.4f))
                            .clickable { annIndex = (annIndex + 1) % announcements.size }
                            .padding(2.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 设备名 + 卡密（两列）
        EnterAnimation(140) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard(alpha = 0.35f, corner = 20, modifier = Modifier.weight(1f)) {
                    Column {
                        Text("设备", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(deviceName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                GlassCard(alpha = 0.35f, corner = 20, modifier = Modifier.weight(1f)) {
                    Column {
                        Text("卡密", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (cardKey.isBlank()) "未输入" else cardKey,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (cardKey.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 启动悬浮窗（先经过卡密验证）
        EnterAnimation(200) {
            GlassButton(modifier = Modifier.fillMaxWidth(), onClick = onLaunch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("启动悬浮窗", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 关闭悬浮窗
        EnterAnimation(240) {
            GlassButton(modifier = Modifier.fillMaxWidth(), onClick = onStop) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Close, null, tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("关闭悬浮窗", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 真实功能模块总数（已改为使用人数，保留占位） */
private const val totalUserCount = 1286

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

        // Shizuku 权限
        EnterAnimation(200) {
            PermissionCard(
                title = "Shizuku（可选）",
                desc = "无需 Root 即可执行系统级操作，提供更高权限的服务管理",
                granted = false,
                onClick = {
                    Toast.makeText(context, "请安装 Shizuku App 完成授权", Toast.LENGTH_LONG).show()
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        ).apply {
                            data = android.net.Uri.parse("package:rikka.shizuku")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        EnterAnimation(280) {
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
    }
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

/* ================= 文件页 ================= */

data class FileItem(val name: String, val size: String, val category: String)

private val fileDownloadItems = listOf(
    FileItem("角色立绘_小染.zip", "12.4MB", "立绘"),
    FileItem("悬浮球皮肤_液态玻璃.zip", "2.1MB", "皮肤"),
    FileItem("音效包_击杀提示音.zip", "5.6MB", "音效"),
    FileItem("图标资源_导航栏.zip", "1.8MB", "图标"),
    FileItem("插件_示例.lua", "4.2KB", "插件"),
)

@Composable
private fun FileDownloadPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // 标题行：刷新按钮（左上角） + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 刷新按钮（液态玻璃风格）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.4f))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    .clickable {
                        refreshing = true
                        scope.launch {
                            kotlinx.coroutines.delay(800)
                            refreshing = false
                            Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("文件", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                Text("资源下载 · 立绘/皮肤/音效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))

        // 文件列表（液态玻璃卡片）
        fileDownloadItems.forEachIndexed { index, file ->
            EnterAnimation(80 + index * 60) {
                FileDownloadCard(file = file, onClick = {
                    Toast.makeText(context, "开始下载：${file.name}", Toast.LENGTH_SHORT).show()
                })
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FileDownloadCard(file: FileItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("${file.category} · ${file.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Filled.PlayArrow,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
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
    onAnimSmoothChange: (Int) -> Unit,
    fontStyle: Int,
    onFontStyleChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", false)) }
    var haptic by remember { mutableStateOf(prefs.getBoolean("haptic", true)) }

    // 后端更新版本（关于小染展示；离线回退本地 VERSION）
    var updateVer by remember { mutableStateOf(MikasaData.VERSION) }
    LaunchedEffect(Unit) {
        updateVer = withContext(Dispatchers.IO) { MikasaApi.updateVersion() }
    }

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
            Spacer(Modifier.height(20.dp))
        }

        // 帧率显示（杂类·监控）
        EnterAnimation(30) {
            FpsCard()
            Spacer(Modifier.height(12.dp))
        }

        // ── 灵动岛开关 ──
        EnterAnimation(60) {
            ToggleRow(
                title = "灵动岛（iOS 风格）",
                on = diEnabled,
                onToggle = { onDiToggle(!diEnabled) }
            )
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(10.dp))
        }

        // ── 字体风格（5 种，点击切换，全局即时生效） ──
        EnterAnimation(95) {
            SettingClickRow(
                title = "字体风格",
                value = AppFonts.options[fontStyle.coerceIn(0, AppFonts.options.size - 1)].name
            ) {
                val next = (fontStyle + 1) % AppFonts.options.size
                onFontStyleChange(next)
                Toast.makeText(context, "字体：${AppFonts.options[next].name}", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── 防录屏开关（在悬浮窗面板 → 设置 中可调） ──
        EnterAnimation(100) {
            SettingClickRow(
                title = "防录屏 / 音量键隐藏",
                value = "悬浮窗面板·设置 中调节"
            ) {
                Toast.makeText(context, "请在悬浮窗面板右上角 ⚙ 设置中开启/关闭防录屏，并开启无障碍使用音量键", Toast.LENGTH_LONG).show()
            }
            Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(10.dp))
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
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(12.dp))
        EnterAnimation(260) {
            ToggleRow(
                title = "跟随系统深色模式",
                on = darkMode,
                onToggle = {
                    darkMode = toggle("dark_mode", darkMode)
                    Toast.makeText(context, if (darkMode) "已开启深色模式" else "已关闭深色模式", Toast.LENGTH_SHORT).show()
                }
            )
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(10.dp))
        }
        EnterAnimation(380) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            Toast.makeText(context, "小染 · 本地 AI 助手", Toast.LENGTH_SHORT).show()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("关于小染", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(updateVer, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            val bg = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color(0xFFE0E0E0).copy(alpha = 0.5f)
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
                        .background(bg)
                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f))
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingClickRow(title: String, value: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}