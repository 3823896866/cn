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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.mikasa.ui.theme.LiquidGlassBackground
import com.mikasa.ui.theme.glassBar
import com.mikasa.ui.theme.glassPanel
import androidx.compose.foundation.isSystemInDarkTheme
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

    // ── AI 聊天状态（持久化：退出 App 仍在；消息保留） ──
    var aiMessages by remember { mutableStateOf(XiaoMiAi.load(prefs.getString("ai_chat", null))) }
    var aiLoading by remember { mutableStateOf(false) }
    // 稳定会话 id + 转人工计时 + 已接收的 agent 回复数
    val csSession = remember {
        var id = prefs.getString("cs_session", null)
        if (id == null) { id = "dev_" + System.currentTimeMillis(); prefs.edit().putString("cs_session", id).apply() }
        id
    }
    var csHumanSince by remember { mutableStateOf(prefs.getLong("cs_human_since", 0L)) }
    var csIngested by remember { mutableIntStateOf(prefs.getInt("cs_ingested_agent", 0)) }
    var csWaitingShown by remember { mutableStateOf(false) }

    // AI 助手后端配置（开关/问候/预设按钮）+ 人工模式
    var aiEnabled by remember { mutableStateOf(true) }
    var aiGreeting by remember { mutableStateOf("") }
    var aiButtons by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var aiHuman by remember { mutableStateOf(false) }
    // AI 助手卡密门槛：验证并绑定卡密后才能使用
    var cardVerified by remember { mutableStateOf(prefs.getString("card_key", "")?.isNotBlank() == true) }

    fun persistChat(list: List<XiaoMiAi.Msg>) {
        prefs.edit().putString("ai_chat", XiaoMiAi.save(list)).apply()
    }

    LaunchedEffect(Unit) {
        scope.launch {
            val cfg = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.aiConfig() }
            aiEnabled = cfg.enabled
            aiGreeting = cfg.greeting
            aiButtons = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.aiButtons() }
            if (aiEnabled) {
                val GREET = "我是小染助手，小染 AI 已就位，有什么不懂的问题来问我吧～"
                val CARD_MSG = "使用 AI 助手需先验证并绑定卡密，见下方输入框～"
                var base = aiMessages
                if (aiGreeting.isNotBlank()) base = listOf(XiaoMiAi.Msg("assistant", aiGreeting)) + base.filter { it.content != GREET }
                if (!cardVerified && base.none { it.content == CARD_MSG }) base = base + XiaoMiAi.Msg("assistant", CARD_MSG)
                aiMessages = base
                persistChat(aiMessages)
            }
        }
    }

    fun sendAiMessage(text: String) {
        val newMsg = XiaoMiAi.Msg("user", text)
        val updated = XiaoMiAi.trimContext(aiMessages + newMsg)
        aiMessages = updated
        persistChat(updated)
        aiLoading = true
        val now = System.currentTimeMillis()
        // 转人工中：不真发；首次提示"等待回复"一次，之后静默等客服在后端回复
        if (csHumanSince > 0 && now - csHumanSince < 10L * 60 * 1000) {
            if (!csWaitingShown) {
                aiMessages = updated + XiaoMiAi.Msg("assistant", "已转人工，正在等待客服回复…")
                persistChat(aiMessages)
                csWaitingShown = true
            }
            aiLoading = false
            return
        }
        if (csHumanSince > 0 && now - csHumanSince >= 10L * 60 * 1000) {
            csHumanSince = 0
            csWaitingShown = false
            prefs.edit().putLong("cs_human_since", 0).apply()
        }
        scope.launch {
            val ck = prefs.getString("card_key", "") ?: ""
            val dev = "${Build.MANUFACTURER} ${Build.MODEL}"
            val res = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.csSend(ck, dev, text, "", csSession) }
            val reply = res?.reply ?: withContext(Dispatchers.IO) { XiaoMiAi.chat(updated) }
            if (res?.status == "human") {
                csHumanSince = now
                csWaitingShown = false
                prefs.edit().putLong("cs_human_since", now).apply()
            }
            val next = XiaoMiAi.trimContext(updated + XiaoMiAi.Msg("assistant", reply))
            aiMessages = next
            persistChat(next)
            aiLoading = false
        }
    }

    fun clearAiChat() {
        aiMessages = listOf(XiaoMiAi.Msg("assistant", "我是小染助手，聊天已清空，随时找我喵~"))
        csIngested = 0
        prefs.edit().remove("ai_chat").putInt("cs_ingested_agent", 0).apply()
    }

    // 轮询接收后端人工(agent)回复（修复"后端发的消息前端收不到"）
    LaunchedEffect(csSession) {
        while (true) {
            delay(8000)
            val agent = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.agentMessages(csSession) }
            val st = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.csStatus(csSession) }
            if (st == "ended" && csHumanSince > 0) {
                csHumanSince = 0
                csWaitingShown = false
                aiMessages = aiMessages + XiaoMiAi.Msg("assistant", "客服已结束服务，恢复正常对话～")
                persistChat(aiMessages)
                prefs.edit().putLong("cs_human_since", 0).apply()
            }
            if (agent.size < csIngested) csIngested = 0
            if (agent.size > csIngested) {
                val fresh = agent.subList(csIngested, agent.size)
                aiMessages = aiMessages + fresh.map { XiaoMiAi.Msg("assistant", it.text, it.image) }
                persistChat(aiMessages)
                csIngested = agent.size
                prefs.edit().putInt("cs_ingested_agent", agent.size).apply()
            }
        }
    }

    // ── 自定义背景图片（软件模糊 + 遮罩，低版本也有效） ──
    var bgImagePath by remember { mutableStateOf(prefs.getString("bg_image", null)) }
    val bgBitmap = remember(bgImagePath) { bgImagePath?.let { decodeBlurBackground(it) } }
    // 视频背景（assets/home_bg.mp4，循环、无控件）
    var videoBg by remember { mutableStateOf(prefs.getBoolean("video_bg", false)) }
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

    // ── 使用人数（后端：当前有效卡密数，卡密到期自动减少） ──
    var userCount by remember { mutableIntStateOf(0) }
    scope.launch { userCount = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.activeUsers() } }

    // ── 下个版本更新内容 + 支持/反馈（后端） ──
    var nextVersion by remember { mutableStateOf("") }
    scope.launch {
        nextVersion = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.nextVersion() }
    }
    fun doSupport() {
        scope.launch {
            withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.support() }
            Toast.makeText(context, "已支持 +1，感谢！", Toast.LENGTH_SHORT).show()
        }
    }
    fun doFeedback(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val dev = "${Build.MANUFACTURER} ${Build.MODEL}"
            val left = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.submitFeedback(text, dev) }
            Toast.makeText(
                context,
                when {
                    left < 0 -> "今日反馈次数已用完（每天最多 3 条）"
                    left == 0 -> "已收到反馈（今日 3 条已用完）"
                    else -> "已收到反馈（今日还可 $left 条）"
                },
                Toast.LENGTH_LONG
            ).show()
        }
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
            // 背景层：视频背景 > 自定义图片（软件模糊+遮罩）> 默认渐变光斑
            if (videoBg) {
                VideoBackground()
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x1A000000))
                )
            } else if (bgBitmap != null) {
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
                LiquidGlassBackground(blurRadius)
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
                            userCount = userCount,
                            todayActivations = todayActivations,
                            nextVersion = nextVersion,
                            onSupport = { doSupport() },
                            onFeedback = { doFeedback(it) },
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
                            onClear = { clearAiChat() },
                            aiEnabled = aiEnabled,
                            aiButtons = aiButtons,
                            onPreset = { label ->
                                val ans = aiButtons.firstOrNull { it.first == label }?.second ?: "（暂无对应回答）"
                                aiMessages = aiMessages + XiaoMiAi.Msg("user", label) + XiaoMiAi.Msg("assistant", ans)
                                persistChat(aiMessages)
                            },
                            humanMode = aiHuman,
                            onTransferHuman = transferLabel@ {
                                if (!cardVerified) {
                                    aiMessages = aiMessages + XiaoMiAi.Msg("assistant", "请先输入并验证卡密，才能使用转人工等所有功能～")
                                    persistChat(aiMessages)
                                    return@transferLabel
                                }
                                aiHuman = true
                                csHumanSince = System.currentTimeMillis()
                                csWaitingShown = false
                                prefs.edit().putLong("cs_human_since", csHumanSince).apply()
                                aiMessages = aiMessages + XiaoMiAi.Msg("assistant", "已为您转接人工客服，请稍候…")
                                persistChat(aiMessages)
                                // 通知后端：记录是哪个用户(卡密/设备)转了人工，后台可直接看到
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.mikasa.ui.XiaoRanApi.csHuman(
                                            prefs.getString("card_key", "") ?: "",
                                            "${Build.MANUFACTURER} ${Build.MODEL}",
                                            csSession
                                        )
                                    }
                                }
                            },
                            cardVerified = cardVerified,
                            onBuy = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.fkdoos.com/shop/24AD8KYO"))
                                    )
                                }.onFailure { Toast.makeText(context, "无法打开购买页", Toast.LENGTH_SHORT).show() }
                            },
                            onVerifyCard = { code ->
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        com.mikasa.ui.XiaoRanApi.verifyCard(code, "${Build.MANUFACTURER} ${Build.MODEL}")
                                    }
                                    if (res.ok) {
                                        prefs.edit().putString("card_key", code).putBoolean("card_verified", true).apply()
                                        cardVerified = true
                                        aiMessages = aiMessages + XiaoMiAi.Msg("assistant", "卡密验证成功，AI 助手已解锁～")
                                        persistChat(aiMessages)
                                        Toast.makeText(context, "卡密已绑定，AI 助手可用", Toast.LENGTH_SHORT).show()
                                    } else {
                                        aiMessages = aiMessages + XiaoMiAi.Msg("assistant", "卡密无效：${res.reason.ifBlank { "请检查后重试" }}")
                                        persistChat(aiMessages)
                                    }
                                }
                            }
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
                            videoBg = videoBg,
                            onVideoBgToggle = {
                                videoBg = it
                                prefs.edit().putBoolean("video_bg", it).apply()
                                Toast.makeText(context, if (it) "已切换为视频背景（循环播放）" else "已关闭视频背景", Toast.LENGTH_SHORT).show()
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
    val dark = isSystemInDarkTheme()
    NavigationBar(
        containerColor = if (dark) Color(0xFF141414).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.5f),
        modifier = Modifier.glassBar(dark)
    ) {
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
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier = modifier.glassPanel(dark).padding(20.dp),
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
    userCount: Int,
    todayActivations: Int,
    nextVersion: String,
    onSupport: () -> Unit,
    onFeedback: (String) -> Unit,
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
            Text("小染自动注入", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
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
                            Text("使用人数", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("$userCount", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
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

        var showFeedbackDialog by remember { mutableStateOf(false) }
        var feedbackText by remember { mutableStateOf("") }

        EnterAnimation(360) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("下个版本更新内容", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (nextVersion.isBlank()) "敬请期待下个版本的更新内容～" else nextVersion,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onSupport, shape = RoundedCornerShape(14.dp)) {
                            Text("支持 ⭐", fontSize = 14.sp)
                        }
                        OutlinedButton(onClick = { showFeedbackDialog = true }, shape = RoundedCornerShape(14.dp)) {
                            Text("反馈更新内容", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (showFeedbackDialog) {
            AlertDialog(
                onDismissRequest = { showFeedbackDialog = false },
                title = { Text("反馈建议") },
                text = {
                    TextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        placeholder = { Text("写下你对下个版本的建议…") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onFeedback(feedbackText)
                        showFeedbackDialog = false
                        feedbackText = ""
                    }) { Text("提交") }
                },
                dismissButton = { TextButton(onClick = { showFeedbackDialog = false }) { Text("取消") } }
            )
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
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE) }
    var showShizuku by remember { mutableStateOf(false) }
    var rootOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { rootOk = withContext(Dispatchers.IO) { rootAvailable() } }

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
                title = "Root 权限",
                desc = "检测系统 Root（Magisk/su）；没有 Root 也可用 无障碍 + Shizuku(无线调试) 获得等效能力",
                granted = rootOk,
                onClick = {
                    scope.launch {
                        rootOk = withContext(Dispatchers.IO) { rootAvailable() }
                        Toast.makeText(context, if (rootOk) "Root 已可用 ✅" else "未检测到 Root（可用 Magisk 获取）", Toast.LENGTH_LONG).show()
                    }
                }
            )
            Spacer(Modifier.height(20.dp))
        }

        // ── Shizuku（开关 + 状态 + 检测） ──
        var shizukuInstalledMemo by remember { mutableStateOf(false) }
        var shizukuConnected by remember { mutableStateOf(false) }
        var shizukuGranted by remember { mutableStateOf(false) }
        var shizukuOn by remember { mutableStateOf(prefs.getBoolean("shizuku_on", false)) }
        val refreshShizuku: () -> Unit = {
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    shizukuInstalled(context) to (shizukuConnected() to shizukuGranted())
                }
                shizukuInstalledMemo = r.first
                shizukuConnected = r.second.first
                shizukuGranted = r.second.second
            }
        }
        // 持续自检测：启动 Shizuku/授权后无需手动点“检测”，状态自动更新
        LaunchedEffect(Unit) {
            while (true) {
                refreshShizuku()
                kotlinx.coroutines.delay(2500)
            }
        }

        // ── Shizuku 终端：输入「小染注入权限」→ 自动授权 + 自动注入已下载文件 ──
        var terminalInput by remember { mutableStateOf("") }
        var terminalRunning by remember { mutableStateOf(false) }
        var terminalLog by remember { mutableStateOf<List<String>>(emptyList()) }
        val termHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
        fun runTerminalCommand() {
            val cmd = terminalInput.trim()
            if (cmd.isBlank() || terminalRunning) return
            terminalRunning = true
            terminalLog = terminalLog + "\$ $cmd"
            scope.launch {
                withContext(Dispatchers.IO) {
                    com.mikasa.ui.ShizukuOps.terminalCommand(context, cmd) { line ->
                        termHandler.post { terminalLog = terminalLog + line }
                    }
                }
                terminalInput = ""
                terminalRunning = false
                refreshShizuku()
            }
        }

        EnterAnimation(360) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Shizuku 权限", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (!shizukuInstalledMemo) "未安装 Shizuku（包名 moe.shizuku.privileged.api，请先安装）"
                                else if (shizukuGranted) "Shizuku 已授权 ✅"
                                else if (shizukuConnected) "Shizuku 已连接，点「授权」获取权限"
                                else "已安装：先「启动 Shizuku」（无线调试/ADB），再「授权」",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = shizukuGranted,
                            enabled = shizukuInstalledMemo,
                            onCheckedChange = { on ->
                                if (!on) {
                                    shizukuOn = false
                                    prefs.edit().putBoolean("shizuku_on", false).apply()
                                    Toast.makeText(context, "Shizuku 模式已关闭", Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { grantShizuku(context) }
                                        refreshShizuku()
                                        shizukuOn = shizukuGranted
                                        prefs.edit().putBoolean("shizuku_on", shizukuOn).apply()
                                        Toast.makeText(
                                            context,
                                            if (shizukuGranted) "Shizuku 已授权 ✅" else if (ok) "Shizuku 已授权" else "未获取到：请先「启动 Shizuku」再授权",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val launched = startShizuku(context)
                                Toast.makeText(
                                    context,
                                    if (launched) "已唤起 Shizuku，请完成启动后点「授权」" else "未找到 Shizuku（包名 moe.shizuku.privileged.api）",
                                    Toast.LENGTH_LONG
                                ).show()
                                delay(1200); refreshShizuku()
                            }
                        }) { Text("启动 Shizuku") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { refreshShizuku(); showShizuku = true }) { Text("授权") }
                    }

                    // ── Shizuku 终端：口令自动授权 + 自动注入 ──
                    Spacer(Modifier.height(14.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1220))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Shizuku 终端", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7FD1FF))
                            Spacer(Modifier.height(3.dp))
                            Text("输入「小染注入权限」→ 自动授权并注入已下载文件", fontSize = 11.sp, color = Color(0xFF8A93A6))
                            Spacer(Modifier.height(8.dp))
                            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(8.dp)) {
                                val logLines = terminalLog.takeLast(8)
                                if (logLines.isEmpty()) {
                                    Text("（等待输入命令…）", fontSize = 11.sp, color = Color(0xFF5A6273), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                                logLines.forEach { ln ->
                                    Text(ln, fontSize = 11.sp, color =
                                        if (ln.startsWith("✅") || ln.startsWith("√")) Color(0xFF5BE3A0)
                                        else if (ln.startsWith("×")) Color(0xFFFF7A7A)
                                        else Color(0xFFC9D1D9),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextField(
                                    value = terminalInput,
                                    onValueChange = { terminalInput = it },
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)),
                                    singleLine = true,
                                    placeholder = { Text("小染注入权限", fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFE6EDF3))
                                )
                                Button(onClick = { runTerminalCommand() }, enabled = !terminalRunning,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                                    Text(if (terminalRunning) "运行中…" else "运行")
                                }
                            }
                        }
                    }
                }
            }
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
            ) { Text("发送授权请求") }
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

/** 检测系统 Root（Magisk/su）：能执行 su -c id 且返回 uid=0。 */
private fun rootAvailable(): Boolean {
    for (cmd in listOf("su", "/system/xbin/su", "/sbin/su", "/system/bin/su", "/system/xbin/magisk")) {
        try {
            val p = ProcessBuilder(cmd, "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0 && out.contains("uid=0")) return true
        } catch (e: Exception) {
        }
    }
    return false
}

/** Shizuku 通道是否已连接（官方 API pingBinder：需 Shizuku App 正通过无线调试/ADB/Root 运行）。 */
private fun shizukuConnected(): Boolean = try { rikka.shizuku.Shizuku.pingBinder() } catch (e: Throwable) { false }

/** Shizuku 是否已授权本应用（checkSelfPermission 返回 0 = PERMISSION_GRANTED）。 */
private fun shizukuGranted(): Boolean = try { rikka.shizuku.Shizuku.checkSelfPermission() == 0 } catch (e: Throwable) { false }

/** Shizuku 是否可用：通道已连接 / 已授权 / 装到已知包名，任一即真（兼容改名/变体）。 */
private fun shizukuInstalled(context: Context): Boolean =
    shizukuConnected() || shizukuGranted() ||
        listOf(
            "moe.shizuku.privileged.api", "moe.shizuku.privilege.api",
            "rikka.shizuku", "dev.rikka.shizuku", "com.rikka.shizuku", "moe.shizuku.shizuku"
        ).any { pkg -> try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false } }

/** 启动/唤起 Shizuku（逐个尝试已知包名，用官方 launch intent，用户在 Shizuku App 内点“启动”建立通道）。 */
private fun startShizuku(context: Context): Boolean {
    val pkgs = listOf(
        "moe.shizuku.privileged.api", "moe.shizuku.privilege.api",
        "rikka.shizuku", "dev.rikka.shizuku", "com.rikka.shizuku"
    )
    for (pkg in pkgs) {
        // 首选官方 launch intent（最可靠，能拉起任意已装 App）
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launch) }.isSuccess) return true
        }
        // 回退：显式组件
        if (runCatching {
            context.startActivity(Intent().apply {
                setComponent(android.content.ComponentName(pkg, "rikka.shizuku.ShizukuActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.isSuccess) return true
    }
    return false
}

/** 发送 Shizuku 授权请求并等待确认（走 ShizukuOps；“授权”按钮/弹窗调用）。返回是否已授权。 */
private fun grantShizuku(context: Context): Boolean =
    try { com.mikasa.ui.ShizukuOps.sendPermissionRequest(context) } catch (e: Throwable) { false }

private fun shizukuStatusText(context: Context): String {
    if (shizukuGranted()) return "Shizuku 已授权 ✅"
    if (shizukuConnected()) return "Shizuku 已连接，点「授权」获取权限"
    return "未检测到已运行的 Shizuku 通道：请把你的 Shizuku/Sui（含改名变体）启动到 Running（无线调试/ADB），再点「授权」或在下方终端输入「小染注入权限」"
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
    // 下载前请求通知权限（Android 13+）；不阻塞下载，用户拒绝只是静默通知、文件照样下完
    val dlPermsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 结果不影响下载 */ }
    fun ensureDlPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) dlPermsLauncher.launch(need.toTypedArray())
    }
    var funcFiles by remember { mutableStateOf<List<com.mikasa.ui.FilesApi.FileItem>>(emptyList()) }
    var beautyFiles by remember { mutableStateOf<List<com.mikasa.ui.FilesApi.FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    // 已下载（本地「小染注入」目录有该文件 → 无需下载）
    var downloaded by remember { mutableStateOf(com.mikasa.ui.FilesApi.downloadedNames(context)) }
    // 后台下载中（DownloadService 推进度；退软件/切页也继续）
    val downloading = com.mikasa.ui.DownloadHub.progress.collectAsState(initial = emptyMap()).value
    // 本地“已点击/进行中”标记：点“下载”瞬间该行就显示“下载中 0%”，不必等后台服务首帧
    var starting by remember { mutableStateOf<Set<String>>(emptySet()) }
    val activeDownloads = (downloading.keys + starting).distinct().associateWith { downloading[it] ?: 0.0 }
    // 清理“已点击/进行中”本地标记：只保留真正在下载中的（DownloadService 首帧即推送）。
    // 下载成功→进入 downloaded 显示“已下载”；失败→还原成“下载”按钮，不再卡 0%。
    LaunchedEffect(downloading, downloaded) {
        starting = starting.filter { it in downloading }.toSet()
    }

    fun loadAll() {
        loading = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { com.mikasa.ui.FilesApi.list("功能") }
            val b = withContext(Dispatchers.IO) { com.mikasa.ui.FilesApi.list("美化") }
            funcFiles = f; beautyFiles = b; loading = false
        }
    }

    LaunchedEffect(Unit) { loadAll() }
    // 下载状态变化（开始/完成）时刷新“已下载”
    LaunchedEffect(downloading) { downloaded = com.mikasa.ui.FilesApi.downloadedNames(context) }

    fun download(item: com.mikasa.ui.FilesApi.FileItem) {
        if (downloaded.contains(item.name)) return        // 已下载 → 无需下载
        if (starting.contains(item.name)) return          // 已点过/下载中 → 不重复（每个文件点一次）
        starting = starting + item.name                    // 立即标记 → 该行变“下载中 0%”
        ensureDlPermissions()                              // 请求通知权限（不阻塞下载）
        // 后台下载服务：前台通知带进度 + 完成通知，退出/切页也继续
        context.startService(
            android.content.Intent(context, com.mikasa.ui.DownloadService::class.java)
                .putExtra("url", com.mikasa.ui.FilesApi.url(item))
                .putExtra("name", item.name)
        )
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
        Text("点击下载即保存到手机；导入 zip 自动解压、同名文件自动替换", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))

        if (loading && funcFiles.isEmpty() && beautyFiles.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("加载中…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FileSection("功能文件", funcFiles, activeDownloads, downloaded) { download(it) }
            Spacer(Modifier.height(16.dp))
            FileSection("美化文件", beautyFiles, activeDownloads, downloaded) { download(it) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FileSection(
    title: String,
    files: List<com.mikasa.ui.FilesApi.FileItem>,
    downloading: Map<String, Double>,
    downloaded: Set<String>,
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
                    val done = downloaded.contains(item.name)
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
                        when {
                            done -> Text("已下载 ✓", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                            prog != null -> Column(Modifier.width(120.dp)) {
                                if (prog < 0) {
                                    // total 未知 → 不定进度（转圈条），不卡 0%
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = -1f,
                                        modifier = Modifier.fillMaxWidth().height(6.dp)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text("下载中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = prog.toFloat(),
                                        modifier = Modifier.fillMaxWidth().height(6.dp)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text("下载中 ${(prog * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            else -> androidx.compose.material3.TextButton(onClick = { onDownload(item) }) { Text("下载") }
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
    videoBg: Boolean,
    onVideoBgToggle: (Boolean) -> Unit,
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
    // 版本号对接后端
    var serverVersion by remember { mutableStateOf("1.0") }
    LaunchedEffect(Unit) { serverVersion = withContext(Dispatchers.IO) { com.mikasa.ui.XiaoRanApi.serverVersion() } }

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
        // ── 视频背景（循环播放 assets/home_bg.mp4，无控件） ──
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ToggleRow(
                title = "视频背景",
                on = videoBg,
                valueText = "home_bg.mp4 · 循环",
                onToggle = { onVideoBgToggle(!videoBg) }
            )
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
                            Toast.makeText(context, "小染注入 $serverVersion · 小染 AI", Toast.LENGTH_SHORT).show()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("关于小染", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(serverVersion, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 视频背景：循环播放 assets/home_bg.mp4，无播放控件。
 *  先把 asset 视频拷到应用缓存文件，再 setDataSource(文件路径) 播放——这是最可靠的姿势
 *  （避免 fd 提前关闭、asset URI 兼容性导致的黑屏）。TextureView 而非 SurfaceView
 *  （在 HorizontalPager 里不挖洞不崩）。重入可复用/重建；全程 try/catch，绝不闪退。 */
@Composable
private fun VideoBackground() {
    val holder = remember { arrayOf<android.media.MediaPlayer?>(null) }
    AndroidView(
        factory = { ctx ->
            val texture = android.view.TextureView(ctx)
            texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    val file = copyAssetVideo(ctx) ?: return
                    if (holder[0] == null) holder[0] = android.media.MediaPlayer()
                    val p = holder[0] ?: return
                    runCatching {
                        p.setSurface(android.view.Surface(st))
                        p.setDataSource(file.absolutePath)
                        p.isLooping = true
                        p.setOnPreparedListener { it.start() }
                        p.setOnErrorListener { pl, _, _ ->
                            runCatching { pl.release() }
                            if (holder[0] === pl) holder[0] = null
                            true
                        }
                        p.prepareAsync()
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                }

                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    runCatching { holder[0]?.release() }
                    holder[0] = null
                    return false
                }

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                }
            }
            texture
        },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
    DisposableEffect(Unit) {
        onDispose {
            runCatching { holder[0]?.release() }
            holder[0] = null
        }
    }
}

/** 把 asset 里的 home_bg.mp4 拷到应用缓存（已存在且非空则复用），返回可播放的文件。 */
private fun copyAssetVideo(ctx: android.content.Context): File? = try {
    val f = File(ctx.cacheDir, "home_bg.mp4")
    if (f.exists() && f.length() > 0) f else {
        ctx.assets.open("home_bg.mp4").use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.length() > 0) f else null
    }
} catch (e: Exception) {
    null
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