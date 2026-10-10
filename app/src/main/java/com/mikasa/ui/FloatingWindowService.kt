package com.mikasa.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mikasa.ui.view.GifMovieView
import com.mikasa.R
import com.mikasa.ui.adapter.FunctionAdapter
import com.mikasa.ui.adapter.NavAdapter
import com.mikasa.ui.util.CharacterArtLoader
import com.mikasa.ui.view.CircleImageView
import java.io.File
import java.io.FileOutputStream

/**
 * 悬浮窗服务
 * 承载二次元风格悬浮窗面板，支持拖动、导航切换、立绘加载等功能
 */
class FloatingWindowService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mikasa_float_channel"
        // 进程级卡密验证状态：关悬浮窗重开仍保持已验证；仅 App 进程被杀后重开才需重验
        @JvmStatic var processCardVerified = false
        @JvmStatic var processCardInfo = ""
        // 录屏授权：透明 Activity 拿到 MediaProjection 后回调给服务
        @JvmStatic var onProjectionGranted: ((android.media.projection.MediaProjection) -> Unit)? = null

        /** 检查悬浮窗权限 */
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        /** 申请悬浮窗权限 */
        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 悬浮球
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    // 拖动相关
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false

    // 面板缩放
    private var scaleFactor: Float = 1.0f
    private var basePanelWidth: Int = 0
    private var basePanelHeight: Int = 0
    private var scaleDetector: ScaleGestureDetector? = null

    // 位置持久化
    private var savedPanelX: Int = Int.MIN_VALUE
    private var savedPanelY: Int = 0
    private var savedScale: Float = 1.0f
    private var savedBallX: Int = Int.MIN_VALUE
    private var savedBallY: Int = 0

    // 适配器
    private lateinit var navAdapter: NavAdapter
    private var tabPagerAdapter: TabPagerAdapter? = null
    private var viewPager: ViewPager2? = null
    private var currentNavPosition: Int = 0
    private var currentTabIndex: Int = 0

    // ── 卡密门 ──
    private var cardVerified = false
    private var cardInfo = ""
    private var cardAnnouncements: List<String> = emptyList()
    private var cardGatePageIndex = -1
    private val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    // 功能/美化 表单页（注入 + 导入方式单选 + 文件单选）
    private var currentFormZone: String? = null
    private val formState = mutableMapOf<String, FormState>()
    private var formSettings: com.mikasa.ui.FilesApi.Settings? = null

    private data class FormState(
        var importDefault: Boolean = true,
        var selectedFile: com.mikasa.ui.FilesApi.FileItem? = null,
        var files: List<com.mikasa.ui.FilesApi.FileItem> = emptyList(),
        var loaded: Boolean = false
    )

    // 长按拖动
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLongPress: Boolean = false
    private var isPanelAnimating: Boolean = false
    private val longPressRunnable = Runnable {
        isLongPress = true
        floatView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // 自定义字体
    private var customTypeface: Typeface? = null

    // 灵动岛（高仿 iOS）
    private var dynamicIsland: DynamicIsland? = null

    // 游戏辅助覆盖层（辅助圆圈 + 准心）
    private var gameOverlay: GameOverlayView? = null
    private var gameOverlayParams: WindowManager.LayoutParams? = null

    // 防录屏：音量键隐藏/显示 UI
    private var uiHiddenByVolume = false

    // 防录屏：录屏自动隐藏（检测到录屏时物理移除，录完自动恢复）
    private var uiHiddenByRecording = false

    /** 广播接收器：音量键控制 + 录屏自动检测（无障碍服务发送） */
    private val volumeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.mikasa.VOLUME_HIDE" -> hideAllUi()
                "com.mikasa.VOLUME_SHOW" -> showAllUi()
                // 录屏开始：防录屏开关开启时自动隐藏 UI（素材干净，绝不黑屏）
                "com.mikasa.RECORDING_ON" -> {
                    if (getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
                            .getBoolean("anti_record", false)
                    ) {
                        hideAllUiForRecording()
                    }
                }
                // 录屏结束：自动恢复
                "com.mikasa.RECORDING_OFF" -> showAllUiForRecording()
            }
        }
    }

    /** 系统音量键监听（不依赖无障碍，直接监听音量变化广播）：音量减=隐藏UI，音量加=显示UI */
    private val sysVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (newVol < 0 || prevVol < 0) return
            when {
                newVol < prevVol -> {
                    // 音量减 → 隐藏UI
                    hideAllUi()
                }
                newVol > prevVol -> {
                    // 音量加 → 显示UI
                    showAllUi()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 初始化立绘加载器
        CharacterArtLoader.init(
            placeholder = R.drawable.ic_placeholder_character,
            avatarPlaceholder = R.drawable.ic_placeholder_character
        )

        // 加载自定义字体
        try {
            customTypeface = Typeface.createFromAsset(assets, "fonts/cream.ttf")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        loadSavedState()
        createNotificationChannel()
        // 注册广播（音量键 + 录屏检测）
        runCatching {
            registerReceiver(
                volumeKeyReceiver,
                IntentFilter("com.mikasa.VOLUME_HIDE").apply {
                    addAction("com.mikasa.VOLUME_SHOW")
                    addAction("com.mikasa.RECORDING_ON")
                    addAction("com.mikasa.RECORDING_OFF")
                }
            )
        }
        // 注册系统音量键广播（不依赖无障碍，音量减=隐藏 / 音量加=显示）
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    sysVolumeReceiver,
                    IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(
                    sysVolumeReceiver,
                    IntentFilter("android.media.VOLUME_CHANGED_ACTION")
                )
            }
        }
    }

    /** 读取保存的悬浮窗位置与缩放 */
    private fun loadSavedState() {
        try {
            val prefs = getSharedPreferences("mikasa_float", MODE_PRIVATE)
            savedPanelX = prefs.getInt("panel_x", Int.MIN_VALUE)
            savedPanelY = prefs.getInt("panel_y", 0)
            savedScale = prefs.getFloat("panel_scale", 1.0f)
            savedBallX = prefs.getInt("ball_x", Int.MIN_VALUE)
            savedBallY = prefs.getInt("ball_y", 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 保存悬浮窗位置与缩放（各自独立保存，互不覆盖；同时更新内存，下次打开立即生效） */
    private fun saveState() {
        try {
            val prefs = getSharedPreferences("mikasa_float", MODE_PRIVATE)
            val edit = prefs.edit()
            // 面板显示时才保存面板位置
            if (floatView != null && layoutParams != null) {
                savedPanelX = layoutParams!!.x
                savedPanelY = layoutParams!!.y
                savedScale = scaleFactor
                edit.putInt("panel_x", savedPanelX)
                    .putInt("panel_y", savedPanelY)
                    .putFloat("panel_scale", savedScale)
            }
            // 悬浮球显示时才保存悬浮球位置
            if (ballView != null && ballParams != null) {
                savedBallX = ballParams!!.x
                savedBallY = ballParams!!.y
                edit.putInt("ball_x", savedBallX)
                    .putInt("ball_y", savedBallY)
            }
            edit.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // 荣耀/HONOR 等系统可能拦截 specialUse 前台服务，失败也不崩溃
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        playStartAudio()
        ensureGameOverlay()

        // 灵动岛（设置里可关闭）
        val diEnabled = getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
            .getBoolean("dynamic_island", true)
        if (diEnabled && dynamicIsland == null) {
            dynamicIsland = DynamicIsland(this).also {
                it.show()
                it.onRecordDotClick = { toggleScreenRecord() }
                it.setRecordDot(getSharedPreferences("mikasa_prefs", MODE_PRIVATE).getBoolean("record_enabled", false))
            }
        }

        // 默认先显示悬浮球
        if (ballView == null && floatView == null) {
            showFloatBall()
        }

        // 处理图片选择结果
        if (intent?.action == "ACTION_IMAGE_SELECTED") {
            val uriString = intent.getStringExtra("image_uri")
            val target = intent.getStringExtra("target") ?: "character"
            uriString?.let { handleSelectedImage(Uri.parse(it), target) }
        }

        return START_STICKY
    }

    /** 显示悬浮球（点击可展开面板） */
    private fun showFloatBall() {
        if (!canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            requestOverlayPermission(this)
            return
        }
        try {
            if (ballView != null) return

            val inflater = LayoutInflater.from(this)
            ballView = inflater.inflate(R.layout.view_float_ball, null)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            ballParams = WindowManager.LayoutParams(
                dpToPx(56), dpToPx(56), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 恢复上次保存的悬浮球位置
                x = if (savedBallX != Int.MIN_VALUE) savedBallX else dpToPx(20)
                y = if (savedBallX != Int.MIN_VALUE) savedBallY else dpToPx(200)
            }

            // 绑定头像
            ballView?.findViewById<CircleImageView>(R.id.iv_ball_avatar)?.let {
                CharacterArtLoader.bindAvatarImageView(this, it)
            }

            // 悬浮球拖动 + 点击展开
            ballView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        initialX = ballParams?.x ?: 0
                        initialY = ballParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            isDragging = true
                        }
                        ballParams?.x = (initialX + dx).toInt()
                        ballParams?.y = (initialY + dy).toInt()
                        if (ballView != null && ballParams != null) {
                            windowManager.updateViewLayout(ballView, ballParams)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 保存悬浮球位置
                        saveState()
                        if (!isDragging) {
                            // 点击展开面板
                            hideFloatBall()
                            showFloatingWindow()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(ballView, ballParams)
            // Q弹弹入动画
            ballView?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.ball_pop_in))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "悬浮球启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 隐藏悬浮球 */
    private fun hideFloatBall() {
        try {
            ballView?.let {
                windowManager.removeView(it)
                ballView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ballView = null
        }
    }

    /** 显示悬浮窗 */
    private fun showFloatingWindow() {
        if (!canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            requestOverlayPermission(this)
            return
        }

        val cp = getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
        // 卡密验证为进程级：关悬浮窗重开仍保持已验证；仅 App 进程被杀后重新打开才需重验
        cardVerified = processCardVerified
        cardInfo = processCardInfo.ifBlank { cp.getString("card_info", "") ?: "" }

        val inflater = LayoutInflater.from(this)
        floatView = inflater.inflate(R.layout.view_floating_panel, null)

        // 设置全局字体
        applyCustomFont(floatView)

        // 初始化布局参数
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 初始尺寸适配屏幕宽度（约85%屏宽），比例保持 700:420
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val designW = dpToPx(700)
        val designH = dpToPx(420)
        basePanelWidth = minOf((screenW * 0.85f).toInt(), designW)
        basePanelHeight = (basePanelWidth * designH / designW).coerceAtMost((screenH * 0.8f).toInt())
        if (basePanelHeight <= 0) basePanelHeight = dpToPx(420)
        scaleFactor = 1.0f

        layoutParams = WindowManager.LayoutParams(
            basePanelWidth,
            basePanelHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 恢复上次保存的位置（首次使用默认值）
            x = if (savedPanelX != Int.MIN_VALUE) savedPanelX else dpToPx(20)
            y = if (savedPanelX != Int.MIN_VALUE) savedPanelY else dpToPx(80)
            // 动态毛玻璃模糊（Android 12+）：轻微35%强度，低融合度
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = dpToPx(35)
            }
        }

        // 恢复保存的缩放比例
        scaleFactor = savedScale.coerceIn(0.45f, 1.5f)
        layoutParams?.width = (basePanelWidth * scaleFactor).toInt()
        layoutParams?.height = (basePanelHeight * scaleFactor).toInt()

        // 设置拖动（按住顶部Tab栏拖动）
        setupDragSupport()

        // 初始化视图
        initViews()

        try {
            windowManager.addView(floatView, layoutParams)
            // Q弹弹入动画（属性动画 overshoot 回弹，确保一定播放）
            floatView?.alpha = 0f
            floatView?.scaleX = 0.7f
            floatView?.scaleY = 0.7f
            floatView?.animate()
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.alpha(1f)
                ?.setDuration(400L)
                ?.setInterpolator(OvershootInterpolator(1.3f))
                ?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            floatView = null
            Toast.makeText(this, "悬浮窗启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            // 失败时退回悬浮球
            showFloatBall()
        }
    }

    /** 初始化所有视图组件 */
    private fun initViews() {
        floatView?.let { view ->
            // 头像和立绘
            val headerAvatar = view.findViewById<CircleImageView>(R.id.iv_header_avatar)
            val illustAvatar = view.findViewById<GifMovieView>(R.id.iv_illust_avatar)
            val characterImage = view.findViewById<ImageView>(R.id.iv_character)

            // 左侧头像保持原样
            CharacterArtLoader.bindAvatarImageView(this, headerAvatar)
            // 右侧上方圆形头像：播放 GIF 动图（assets/avatar_anim.gif）
            runCatching { illustAvatar.setGif(assets.open("avatar_anim.gif")) }
            CharacterArtLoader.bindCharacterImageView(this, characterImage)

            // 关闭按钮
            view.findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
                hideFloatingWindow()
            }

            // 返回前进
            view.findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
                Toast.makeText(this, "返回", Toast.LENGTH_SHORT).show()
            }
            view.findViewById<ImageButton>(R.id.btn_forward).setOnClickListener {
                Toast.makeText(this, "前进", Toast.LENGTH_SHORT).show()
            }

            // 底部选择器
            view.findViewById<ImageButton>(R.id.btn_selector).setOnClickListener {
                Toast.makeText(this, "选择器", Toast.LENGTH_SHORT).show()
            }

            // 缩放按钮
            view.findViewById<ImageButton>(R.id.btn_zoom_in).setOnClickListener {
                zoomPanel(1.15f)
            }
            view.findViewById<ImageButton>(R.id.btn_zoom_out).setOnClickListener {
                zoomPanel(0.87f)
            }

            // 立绘区域点击切换
            view.findViewById<FrameLayout>(R.id.illust_area).setOnClickListener {
                openImagePicker("character")
            }

            // 清除冻结按钮（右侧雪花）
            view.findViewById<ImageButton>(R.id.btn_fab).setOnClickListener {
                showFloatToast("清除冻结成功")
            }

            // 初始化导航和内容
            initNav(view)
            initViewPager(view)

            // 默认加载"人物"标签页
            loadNavContent(currentNavPosition)
        }
    }

    /** 初始化左侧导航 */
    private fun initNav(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_nav)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val navItems = listOf(
            NavAdapter.NavItem(R.drawable.ic_nav_home, getString(R.string.nav_home)),
            NavAdapter.NavItem(R.drawable.ic_nav_person, getString(R.string.nav_person)),
            NavAdapter.NavItem(R.drawable.ic_nav_gun, getString(R.string.nav_gun)),
            NavAdapter.NavItem(R.drawable.ic_nav_misc, getString(R.string.nav_misc)),
            NavAdapter.NavItem(R.drawable.ic_nav_settings, getString(R.string.nav_settings))
        )

        navAdapter = NavAdapter(navItems) { position, _ ->
            if (position != 0 && !cardVerified) {
                showFloatToast("请先输入卡密解锁")
            } else {
                loadNavContent(position)
            }
        }

        recyclerView.adapter = navAdapter
    }

    /** 初始化ViewPager2内容区（左右滑动切换Tab） */
    private fun initViewPager(view: View) {
        val vp = view.findViewById<ViewPager2>(R.id.vp_content)
        viewPager = vp
        tabPagerAdapter = TabPagerAdapter()
        vp.adapter = tabPagerAdapter

        // 页面切换动画：缩放 + 淡入淡出（谷歌Material风格）
        vp.setPageTransformer { page, position ->
            val absPos = kotlin.math.abs(position)
            page.alpha = 1f - absPos * 0.35f
            page.scaleX = 1f - absPos * 0.10f
            page.scaleY = 1f - absPos * 0.10f
        }

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentTabIndex = position
                updateTabSelection(position)
            }
        })
    }

    /** ViewPager2页面适配器：每页一个功能列表 */
    private inner class TabPagerAdapter : RecyclerView.Adapter<TabPagerAdapter.PageHolder>() {
        private val pages = mutableListOf<List<FunctionAdapter.FunctionItem>>()
        private val adapters = mutableListOf<FunctionAdapter>()

        inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val recycler: RecyclerView = itemView.findViewById(R.id.rv_page)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(this@FloatingWindowService)
                .inflate(R.layout.item_page, parent, false)
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val rv = holder.recycler
            // 纯文字信息页（首页卡密页）用单列布局，公告/卡密/设备依次叠放
            val isSingle = pages[position].any {
                it.type == FunctionAdapter.TYPE_TEXT || it.type == FunctionAdapter.TYPE_HEADER ||
                    it.type == FunctionAdapter.TYPE_BUTTON || it.type == FunctionAdapter.TYPE_RADIO
            }
            rv.layoutManager = if (isSingle)
                LinearLayoutManager(this@FloatingWindowService)
            else
                GridLayoutManager(this@FloatingWindowService, 2)
            while (adapters.size <= position) adapters.add(FunctionAdapter())
            val adapter = adapters[position]
            adapter.setItems(pages[position])
            // 开关切换 → 自定义消息通知
            adapter.onToggle = { name, checked ->
                when (name) {
                    "录屏" -> handleRecordToggle(checked)
                    "显示准心" -> { getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putBoolean("cross_enabled", checked).apply(); updateGameOverlay() }
                    "辅助圆圈开关" -> { getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putBoolean("circle_enabled", checked).apply(); updateGameOverlay() }
                    else -> {
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putBoolean("toggle_$name", checked).apply()
                        showFloatToast("${if (checked) "已开启" else "已关闭"} · $name")
                    }
                }
            }
            adapter.onSlider = { name, value ->
                when (name) {
                    "辅助圆圈大小" -> {
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putInt("circle_size", value).apply()
                        updateGameOverlay()
                    }
                    "准心大小" -> {
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putInt("cross_size", value).apply()
                        updateGameOverlay()
                    }
                    "准心位置 X" -> {
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putInt("cross_x", value).apply()
                        updateGameOverlay()
                    }
                    "准心位置 Y" -> {
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putInt("cross_y", value).apply()
                        updateGameOverlay()
                    }
                }
            }
            // 表单页（功能/美化）：注入按钮 + 单选（导入方式/文件）
            adapter.onButtonClick = { name -> if (currentFormZone != null) runInject(name) }
            adapter.onRadio = { group, name ->
                when (group) {
                    "crossType" -> {
                        val i = if (name == "纯十字") 1 else if (name == "菱形") 2 else 0
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putInt("cross_type", i).apply()
                        updateGameOverlay(); refreshMiscPage()
                    }
                    "crossColor" -> {
                        val hex = when (name) { "绿色" -> "#FF00E676"; "黄色" -> "#FFFFEB3B"; "蓝色" -> "#FF2196F3"; "白色" -> "#FFFFFFFF"; else -> "#FFFF0000" }
                        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putString("cross_color", hex).apply()
                        updateGameOverlay(); refreshMiscPage()
                    }
                    else -> if (currentFormZone != null) onFormRadio(group, name)
                }
            }
            // 卡密门：首页未验证 → 显示「输入框+验证按钮」，隐藏功能列表
            val gate = holder.itemView.findViewById<android.view.View>(R.id.card_gate)
            if (position == cardGatePageIndex && !cardVerified) {
                gate?.visibility = android.view.View.VISIBLE
                rv.visibility = android.view.View.GONE
                val etCard = gate?.findViewById<android.widget.EditText>(R.id.et_card)
                // 记住上次输入的卡密（prefill），但不自动登录，仍需点确认
                val savedKey = getSharedPreferences("mikasa_prefs", MODE_PRIVATE).getString("card_key", "")
                if (!savedKey.isNullOrEmpty() && etCard?.text?.toString().isNullOrEmpty()) {
                    etCard?.setText(savedKey)
                }
                gate?.findViewById<android.widget.Button>(R.id.btn_verify)?.setOnClickListener {
                    verifyCardKey(etCard?.text?.toString()?.trim() ?: "")
                }
                etCard?.let { applyFocusToggle(it) }
            } else {
                gate?.visibility = android.view.View.GONE
                rv.visibility = android.view.View.VISIBLE
            }
            rv.adapter = adapter
            // 列表项进场动画
            rv.layoutAnimation = AnimationUtils.loadLayoutAnimation(
                this@FloatingWindowService, R.anim.rv_layout_anim
            )
            rv.scheduleLayoutAnimation()
        }

        override fun getItemCount(): Int = pages.size

        fun setPages(newPages: List<List<FunctionAdapter.FunctionItem>>) {
            pages.clear()
            pages.addAll(newPages)
            adapters.clear()
            notifyDataSetChanged()
        }

        fun getAdapter(index: Int): FunctionAdapter? = adapters.getOrNull(index)
    }

    /** 功能/美化 表单页条目：注入按钮 + 导入方式(单选) + 文件(单选) */
    private fun formItemsForZone(zone: String): List<FunctionAdapter.FunctionItem> {
        val st = formState.getOrPut(zone) { FormState() }
        val items = mutableListOf<FunctionAdapter.FunctionItem>()
        items.add(FunctionAdapter.FunctionItem(zone, type = FunctionAdapter.TYPE_HEADER))
        items.add(FunctionAdapter.FunctionItem("注入", type = FunctionAdapter.TYPE_BUTTON, subtitle = if (shizukuGranted()) "已授权 Shizuku" else "需 Shizuku 授权"))
        items.add(FunctionAdapter.FunctionItem("导入方式", type = FunctionAdapter.TYPE_HEADER))
        items.add(FunctionAdapter.FunctionItem("默认导入", group = "import", isChecked = st.importDefault,
            type = FunctionAdapter.TYPE_RADIO, subtitle = importPathSubtitle(true)))
        items.add(FunctionAdapter.FunctionItem("pak导入", group = "import", isChecked = !st.importDefault,
            type = FunctionAdapter.TYPE_RADIO, subtitle = importPathSubtitle(false)))
        items.add(FunctionAdapter.FunctionItem(if (zone == "功能") "功能文件" else "美化文件", type = FunctionAdapter.TYPE_HEADER))
        if (!st.loaded) items.add(FunctionAdapter.FunctionItem("加载中…", type = FunctionAdapter.TYPE_TEXT))
        else if (st.files.isEmpty()) items.add(FunctionAdapter.FunctionItem("暂无文件（后端未上传该分区）", type = FunctionAdapter.TYPE_TEXT))
        else st.files.forEach { f ->
            val dl = com.mikasa.ui.FilesApi.isDownloaded(applicationContext, f)
            items.add(FunctionAdapter.FunctionItem(
                f.name, group = "file",
                isChecked = st.selectedFile?.name == f.name && dl,
                type = FunctionAdapter.TYPE_RADIO,
                subtitle = "${f.size} · ${if (dl) "已下载" else "未下载（先下载）"}",
                enabled = dl
            ))
        }
        return items
    }

    /** 导入路径（对接后端 settings；未配置则提示） */
    private fun importPathSubtitle(useDefault: Boolean): String {
        val p = if (useDefault) formSettings?.importPathDefault else formSettings?.importPathPak
        return if (p.isNullOrBlank()) "（未配置导入路径）" else p
    }

    /** 后台加载该分区文件列表并刷新表单页 */
    private fun loadFormFiles(zone: String) {
        val st = formState.getOrPut(zone) { FormState() }
        Thread {
            com.mikasa.ui.FilesApi.settings()?.let { mainHandler.post { formSettings = it } }
            val files = com.mikasa.ui.FilesApi.list(zone)
            mainHandler.post {
                st.files = files; st.loaded = true
                if (st.selectedFile == null) st.selectedFile = files.firstOrNull { com.mikasa.ui.FilesApi.isDownloaded(applicationContext, it) }
                refreshFormPage()
            }
        }.start()
    }

    /** 刷新当前表单页 */
    private fun refreshFormPage() {
        val zone = currentFormZone ?: return
        val st = formState[zone] ?: return
        tabPagerAdapter?.getAdapter(0)?.setItems(formItemsForZone(zone))
    }

    /** 表单单选：import（导入方式互斥）/ file（文件互斥） */
    private fun onFormRadio(group: String, name: String) {
        val zone = currentFormZone ?: return
        val st = formState.getOrPut(zone) { FormState() }
        when (group) {
            "import" -> st.importDefault = (name == "默认导入")
            "file" -> st.selectedFile = st.files.firstOrNull { it.name == name }
        }
        refreshFormPage()
    }

    /** Shizuku 是否已授权本应用（写入目标游戏目录必须）。 */
    private fun shizukuGranted(): Boolean = try { rikka.shizuku.Shizuku.checkSelfPermission() == 0 } catch (e: Throwable) { false }

    /** 注入：按所选文件 + 导入方式，用 Shizuku 提权真实写入目标游戏目录。结果走系统 Toast（可靠）+ 悬浮 Toast。 */
    private fun runInject(buttonName: String) {
        val zone = currentFormZone ?: return
        val st = formState.getOrPut(zone) { FormState() }
        val sel = st.selectedFile
        if (sel == null) { notifyInject("请先选择一个已下载的文件"); return }
        if (!com.mikasa.ui.FilesApi.isDownloaded(applicationContext, sel)) {
            notifyInject("「${sel.name}」尚未下载，请到「文件」页下载后再注入"); return
        }
        val path = if (st.importDefault) formSettings?.importPathDefault else formSettings?.importPathPak
        if (path.isNullOrBlank()) {
            notifyInject("后端未配置导入路径（${if (st.importDefault) "默认" else "pak"}）"); return
        }
        notifyInject("开始注入「${sel.name}」→ 目标目录…")
        Thread {
            val (ok, msg) = com.mikasa.ui.ShizukuOps.privilegedInject(applicationContext, sel.name, path) {}
            mainHandler.post { notifyInject(msg) }
        }.start()
    }

    /** 注入/权限反馈：同时用系统 Toast（一定显示）+ 悬浮 Toast，杜绝“没反应、没提示”。 */
    private fun notifyInject(msg: String) {
        showFloatToast(msg)
        runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun miscPageItems(): List<FunctionAdapter.FunctionItem> {
        val prefs = getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
        val t = { n: String -> prefs.getBoolean("toggle_$n", false) }
        val H = FunctionAdapter.TYPE_HEADER
        val G = FunctionAdapter.TYPE_GROUP
        val C = FunctionAdapter.TYPE_GROUP_CHILD
        val R = FunctionAdapter.TYPE_RADIO
        val S = FunctionAdapter.TYPE_SWITCH
        val crossType = prefs.getInt("cross_type", 0)
        val crossColor = prefs.getString("cross_color", "#FFFF0000") ?: "#FFFF0000"
        val list = mutableListOf<FunctionAdapter.FunctionItem>()
        list.add(FunctionAdapter.FunctionItem("杂类", type = H))
        list.add(FunctionAdapter.FunctionItem("准心辅助", type = G, expanded = true, group = "assist"))
        list.add(FunctionAdapter.FunctionItem("显示准心", type = C, group = "assist", isChecked = prefs.getBoolean("cross_enabled", false)))
        list.add(FunctionAdapter.FunctionItem("自动锁定", type = C, group = "assist", isChecked = t("自动锁定")))
        list.add(FunctionAdapter.FunctionItem("吸附对齐", type = C, group = "assist", isChecked = t("吸附对齐")))
        list.add(FunctionAdapter.FunctionItem("高亮标记", type = C, group = "assist", isChecked = t("高亮标记")))
        list.add(FunctionAdapter.FunctionItem("穿透辅助", type = C, group = "assist", isChecked = t("穿透辅助")))
        list.add(FunctionAdapter.FunctionItem("准心类型", type = H))
        listOf("圆环十字" to 0, "纯十字" to 1, "菱形" to 2).forEach { (n, i) ->
            list.add(FunctionAdapter.FunctionItem(n, type = R, group = "crossType", isChecked = crossType == i))
        }
        list.add(FunctionAdapter.FunctionItem("准心颜色", type = H))
        listOf("红色" to "#FFFF0000", "绿色" to "#FF00E676", "黄色" to "#FFFFEB3B", "蓝色" to "#FF2196F3", "白色" to "#FFFFFFFF").forEach { (n, hex) ->
            list.add(FunctionAdapter.FunctionItem(n, type = R, group = "crossColor", isChecked = crossColor == hex, subtitle = hex))
        }
        list.add(FunctionAdapter.FunctionItem("准心大小", type = FunctionAdapter.TYPE_SLIDER, sliderValue = prefs.getInt("cross_size", 40), sliderMax = 100))
        list.add(FunctionAdapter.FunctionItem("准心位置 X", type = FunctionAdapter.TYPE_SLIDER, sliderValue = prefs.getInt("cross_x", 50), sliderMax = 100))
        list.add(FunctionAdapter.FunctionItem("准心位置 Y", type = FunctionAdapter.TYPE_SLIDER, sliderValue = prefs.getInt("cross_y", 50), sliderMax = 100))
        list.add(FunctionAdapter.FunctionItem("辅助圆圈", type = H))
        list.add(FunctionAdapter.FunctionItem("辅助圆圈开关", type = S, group = "circle", isChecked = prefs.getBoolean("circle_enabled", false)))
        list.add(FunctionAdapter.FunctionItem("辅助圆圈大小", type = FunctionAdapter.TYPE_SLIDER, sliderValue = prefs.getInt("circle_size", 40), sliderMax = 100))
        list.add(FunctionAdapter.FunctionItem("录屏", type = H))
        list.add(FunctionAdapter.FunctionItem("录屏", type = S, group = "record", isChecked = prefs.getBoolean("record_enabled", false)))
        return list
    }

    private var screenRecording = false
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var audioPlayer: android.media.MediaPlayer? = null

    /** 开启悬浮窗时播放上传的音频（assets/start_audio.mp3） */
    private fun playStartAudio() {
        try {
            audioPlayer?.let { runCatching { it.release() } }
            val fd = assets.openFd("start_audio.mp3")
            val mp = android.media.MediaPlayer()
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.prepare()
            mp.start()
            audioPlayer = mp
        } catch (e: Exception) {
        }
    }

    private fun handleRecordToggle(on: Boolean) {
        getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit().putBoolean("record_enabled", on).apply()
        dynamicIsland?.setRecordDot(on)
        if (on) showFloatToast("录屏已开启：点灵动岛红点开始录制")
        else { stopScreenRecord(); showFloatToast("录屏已关闭") }
    }

    fun toggleScreenRecord() { if (screenRecording) stopScreenRecord() else requestScreenRecord() }

    /** 请求系统录屏授权，授权后开始真实屏幕录制 */
    private fun requestScreenRecord() {
        try {
            onProjectionGranted = { proj -> mainHandler.post { startScreenRecord(proj) } }
            val act = android.content.Intent(this, ProjectionHostActivity::class.java)
            act.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(act)
        } catch (e: Exception) {
            showFloatToast("无法请求录屏授权")
        }
    }

    private fun startScreenRecord(proj: android.media.projection.MediaProjection) {
        if (screenRecording) return
        try {
            val file = java.io.File(getExternalFilesDir(null) ?: cacheDir, "screen_${System.currentTimeMillis()}.mp4")
            val r = android.media.MediaRecorder()
            r.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(720, 1280)
            r.setVideoEncodingBitRate(8_000_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            val dm = resources.displayMetrics
            val vd = proj.createVirtualDisplay(
                "xiaoran_screen_rec", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                0, r.surface, null, mainHandler
            )
            r.start()
            mediaRecorder = r; virtualDisplay = vd; mediaProjection = proj; screenRecording = true
            showFloatToast("开始录屏 → ${file.name}")
        } catch (e: Exception) {
            showFloatToast("录屏失败：${e.message}")
            runCatching { virtualDisplay?.release() }
            runCatching { mediaRecorder?.release() }
            virtualDisplay = null; mediaRecorder = null
        }
    }

    private fun stopScreenRecord() {
        if (!screenRecording && mediaRecorder == null && mediaProjection == null) return
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release()
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        mediaRecorder = null; virtualDisplay = null; mediaProjection = null
        screenRecording = false
        showFloatToast("已停止录屏")
    }

    /** 游戏辅助覆盖层：全屏透明悬浮层，画圆圈/准心（不拦截触摸） */
    private fun ensureGameOverlay() {
        if (gameOverlay != null) return
        try {
            val v = GameOverlayView(this)
            val type = if (android.os.Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            windowManager.addView(v, p)
            gameOverlay = v
            gameOverlayParams = p
            updateGameOverlay()
        } catch (e: Exception) {
            showFloatToast("辅助覆盖层需悬浮窗权限")
        }
    }

    private fun updateGameOverlay() {
        val v = gameOverlay ?: return
        val prefs = getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
        v.setCircle(prefs.getBoolean("circle_enabled", false), prefs.getInt("circle_size", 40))
        v.applyCrossSize(prefs.getInt("cross_size", 40))
        v.applyCrossPosition(prefs.getInt("cross_x", 50), prefs.getInt("cross_y", 50))
        v.setCross(
            prefs.getBoolean("cross_enabled", false),
            prefs.getInt("cross_type", 0),
            android.graphics.Color.parseColor(prefs.getString("cross_color", "#FFFF0000") ?: "#FFFF0000")
        )
    }

    private fun removeGameOverlay() {
        gameOverlay?.let { runCatching { windowManager.removeView(it) } }
        gameOverlay = null
        gameOverlayParams = null
    }

    /** 刷新杂类页（准心/圆圈状态变更后重绘列表） */
    private fun refreshMiscPage() {
        if (currentNavPosition != 3) return
        tabPagerAdapter?.getAdapter(0)?.setItems(miscPageItems())
    }

    /** 根据导航项生成 Tab 与页面数据 */
    private fun buildTabData(navPosition: Int): Pair<List<String>, List<List<FunctionAdapter.FunctionItem>>> {
        if (navPosition == 0) {
            return listOf("卡密") to listOf(cardGateItems())
        }
        // 功能/美化 页：单页表单（注入 + 导入方式单选 + 文件单选）
        val zone = when (navPosition) {
            1 -> "功能"
            2 -> "美化"
            else -> null
        }
        if (zone != null) {
            val title = if (zone == "功能") "功能" else "美化人物"
            return listOf(title) to listOf(formItemsForZone(zone))
        }
        val tabs = when (navPosition) {
            3 -> listOf("杂类")
            4 -> listOf(getString(R.string.tab_general), getString(R.string.tab_about))
            else -> listOf(getString(R.string.tab_home))
        }

        val pages = tabs.map { tabName ->
            when (tabName) {
                "杂类" -> miscPageItems()
                getString(R.string.tab_general) -> listOf(
                    FunctionAdapter.FunctionItem("自动更新"),
                    FunctionAdapter.FunctionItem("性能优化"),
                    FunctionAdapter.FunctionItem("通知设置")
                )
                getString(R.string.tab_about) -> listOf(
                    FunctionAdapter.FunctionItem("版本信息"),
                    FunctionAdapter.FunctionItem("开发者"),
                    FunctionAdapter.FunctionItem("开源协议")
                )
                else -> listOf(
                    // ── 示例1：折叠（LuaBox 风格：点击展开/折叠，展开后是勾选功能列表） ──
                    FunctionAdapter.FunctionItem(
                        "折叠示例", type = FunctionAdapter.TYPE_GROUP, expanded = true
                    ),
                    FunctionAdapter.FunctionItem(
                        "功能一", type = FunctionAdapter.TYPE_GROUP_CHILD
                    ),
                    FunctionAdapter.FunctionItem(
                        "功能二", type = FunctionAdapter.TYPE_GROUP_CHILD
                    ),
                    FunctionAdapter.FunctionItem(
                        "功能三", type = FunctionAdapter.TYPE_GROUP_CHILD
                    ),
                    // ── 示例2：按钮（开关按钮：点击切换开/关） ──
                    FunctionAdapter.FunctionItem(
                        "按钮示例", type = FunctionAdapter.TYPE_SWITCH, isChecked = false
                    ),
                    FunctionAdapter.FunctionItem("选择左侧功能开始")
                )
            }
        }
        return tabs to pages
    }

    /** 根据导航项加载对应内容 */
    private fun loadNavContent(navPosition: Int) {
        currentNavPosition = navPosition
        currentTabIndex = 0
        cardGatePageIndex = if (navPosition == 0) 0 else -1
        currentFormZone = if (navPosition == 1) "功能" else if (navPosition == 2) "美化" else null
        val (tabs, pages) = buildTabData(navPosition)
        tabPagerAdapter?.setPages(pages)
        updateTabViews(tabs)
        viewPager?.setCurrentItem(0, false)
        currentFormZone?.let { loadFormFiles(it) }
    }

    /** 卡密门：首页功能项 */
    private fun cardGateItems(): List<FunctionAdapter.FunctionItem> {
        if (!cardVerified) return emptyList()
        val t = FunctionAdapter.TYPE_TEXT
        val items = mutableListOf<FunctionAdapter.FunctionItem>()
        // 顶部一句问候
        items.add(FunctionAdapter.FunctionItem("小染祝你天天开心～", type = t))
        // 公告在上
        items.add(FunctionAdapter.FunctionItem("公告", type = t))
        if (cardAnnouncements.isEmpty()) {
            items.add(FunctionAdapter.FunctionItem("暂无公告", type = t))
        } else {
            cardAnnouncements.forEach { items.add(FunctionAdapter.FunctionItem(it, type = t)) }
        }
        // 卡密与设备信息在公告下方
        items.add(FunctionAdapter.FunctionItem("卡密：$cardInfo", type = t))
        items.add(FunctionAdapter.FunctionItem("设备：$deviceName", type = t))
        return items
    }

    /** 卡密输入框聚焦时临时允许窗口收焦点(弹键盘)，失焦恢复 */
    private fun applyFocusToggle(v: android.view.View) {
        v.setOnFocusChangeListener { _, hasFocus ->
            val lp = layoutParams ?: return@setOnFocusChangeListener
            try {
                lp.flags = if (hasFocus)
                    (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                else
                    lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager.updateViewLayout(floatView, lp)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /** 验证卡密（后端）：成功→解锁并刷新首页，失败→提示 */
    private fun verifyCardKey(code: String) {
        if (code.isEmpty()) { showFloatToast("请先在搜索框输入卡密"); return }
        Thread {
            val r = com.mikasa.ui.XiaoRanApi.verifyCard(code, deviceName)
            val ann = if (r.ok) com.mikasa.ui.XiaoRanApi.announcements() else emptyList()
            mainHandler.post {
                if (r.ok) {
                    cardVerified = true
                    cardInfo = "类型 ${r.type} · 到期 ${r.expiresAt.ifBlank { "永久" }}"
                    processCardVerified = true
                    processCardInfo = cardInfo
                    cardAnnouncements = ann
                    getSharedPreferences("mikasa_prefs", MODE_PRIVATE).edit()
                        .putBoolean("card_verified", true)
                        .putString("card_key", code)
                        .putString("card_info", cardInfo)
                        .apply()
                    loadNavContent(0)
                    showFloatToast("卡密验证成功，已解锁")
                } else {
                    showFloatToast("卡密无效：${r.reason.ifBlank { "未知" }}")
                }
            }
        }.start()
    }

    /** 更新顶部Tab视图（点击联动ViewPager左右滑动） */
    private fun updateTabViews(tabs: List<String>) {
        floatView?.let { view ->
            val container = view.findViewById<LinearLayout>(R.id.tab_container)
            container.removeAllViews()

            tabs.forEachIndexed { index, tabName ->
                val tabView = LayoutInflater.from(this).inflate(R.layout.item_tab, container, false)
                val textView = tabView.findViewById<TextView>(R.id.tv_tab)
                textView.text = tabName
                customTypeface?.let { textView.typeface = it }

                tabView.setOnClickListener {
                    if (viewPager?.currentItem != index) {
                        viewPager?.setCurrentItem(index, true)
                    }
                }
                // 长按放大且变色（拖拽重排为后续项，当前给视觉反馈）
                tabView.setOnLongClickListener {
                    tabView.animate().scaleX(1.35f).scaleY(1.35f).setDuration(130).start()
                    tabView.setBackgroundColor(getColor(R.color.primary_dark))
                    textView.setTextColor(getColor(R.color.white))
                    showFloatToast("长按放大：拖动可切换该页签")
                    true
                }
                tabView.setOnTouchListener { v, ev ->
                    if (ev.action == android.view.MotionEvent.ACTION_UP ||
                        ev.action == android.view.MotionEvent.ACTION_CANCEL
                    ) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }
                    false
                }

                container.addView(tabView)
            }
            updateTabSelection(0)
        }
    }

    /** 更新Tab选中高亮 */
    private fun updateTabSelection(selectedIndex: Int) {
        floatView?.let { view ->
            val container = view.findViewById<LinearLayout>(R.id.tab_container)
            for (i in 0 until container.childCount) {
                val tabView = container.getChildAt(i)
                val textView = tabView.findViewById<TextView>(R.id.tv_tab)
                if (i == selectedIndex) {
                    tabView.setBackgroundResource(R.drawable.bg_tab_selected)
                    textView.setTextColor(getColor(R.color.white))
                } else {
                    tabView.setBackgroundResource(R.drawable.bg_tab_normal)
                    textView.setTextColor(getColor(R.color.text_primary))
                }
            }
        }
    }

    /** 设置拖动支持：长按面板移动 + 双指缩放，按钮/列表滚动不受影响 */
    private fun setupDragSupport() {
        val panel = floatView ?: return
        if (basePanelWidth == 0) basePanelWidth = dpToPx(700)
        if (basePanelHeight == 0) basePanelHeight = dpToPx(420)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (floatView == null || layoutParams == null) return false
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.45f, 1.5f)
                layoutParams?.width = (basePanelWidth * scaleFactor).toInt()
                layoutParams?.height = (basePanelHeight * scaleFactor).toInt()
                try {
                    windowManager.updateViewLayout(floatView, layoutParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return true
            }
        })

        panel.setOnTouchListener { _, event ->
            // 双指缩放优先（空白区域拦截后能收到事件）
            scaleDetector?.onTouchEvent(event)
            if (scaleDetector?.isInProgress == true) {
                panel.removeCallbacks(longPressRunnable)
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 触摸点落在可交互控件上则交给子View（按钮/输入框/列表滚动）
                    if (findInteractiveChildAt(panel, event.x, event.y) != null) {
                        isLongPress = false
                        isDragging = false
                        return@setOnTouchListener false
                    }
                    // 空白区域：拦截，启动长按计时
                    isLongPress = false
                    isDragging = false
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    panel.removeCallbacks(longPressRunnable)
                    panel.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isLongPress &&
                        (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                    ) {
                        // 长按前就滑动：取消长按（空白区域不响应快速滑动）
                        panel.removeCallbacks(longPressRunnable)
                    }
                    if (isLongPress) {
                        // 长按后移动面板
                        layoutParams?.x = (initialX + dx).toInt()
                        layoutParams?.y = (initialY + dy).toInt()
                        if (floatView != null && layoutParams != null) {
                            try {
                                windowManager.updateViewLayout(floatView, layoutParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    panel.removeCallbacks(longPressRunnable)
                    val wasLongPress = isLongPress
                    isLongPress = false
                    if (wasLongPress) {
                        // 长按拖动结束，震动反馈 + 保存位置
                        panel.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        if (event.action == MotionEvent.ACTION_UP) saveState()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 查找触摸点下可交互的子View（按钮/输入框/滚动容器等） */
    private fun findInteractiveChildAt(view: View, x: Float, y: Float): View? {
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val localX = x - child.left
                val localY = y - child.top
                if (localX in 0f..child.width.toFloat() && localY in 0f..child.height.toFloat()) {
                    val deeper = findInteractiveChildAt(child, localX, localY)
                    if (deeper != null) return deeper
                }
            }
        }
        return if (view.isClickable || view.isLongClickable || view.isFocusable ||
            view is EditText ||
            view is android.widget.AbsListView ||
            view is RecyclerView ||
            view is ViewPager2 ||
            view is android.widget.HorizontalScrollView ||
            view is android.widget.ScrollView
        ) view else null
    }

    /** 自制悬浮消息通知：深色长条，显示约1.5秒后自动消失 */
    private fun showFloatToast(message: String, iconRes: Int = R.drawable.ic_check_white) {
        try {
            val toastView = LayoutInflater.from(this).inflate(R.layout.view_float_toast, null)
            toastView.findViewById<TextView>(R.id.tv_toast_text).text = message
            toastView.findViewById<ImageView>(R.id.iv_toast_icon).setImageResource(iconRes)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(150)
            }
            windowManager.addView(toastView, params)
            toastView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.toast_pop_in))

            // 1.5秒后淡出消失
            mainHandler.postDelayed({
                try {
                    toastView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.toast_pop_out))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mainHandler.postDelayed({
                    try {
                        windowManager.removeView(toastView)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, 300L)
            }, 1500L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 按钮缩放面板 */
    private fun zoomPanel(factor: Float) {
        if (floatView == null || layoutParams == null) return
        scaleFactor = (scaleFactor * factor).coerceIn(0.45f, 1.5f)
        layoutParams?.width = (basePanelWidth * scaleFactor).toInt()
        layoutParams?.height = (basePanelHeight * scaleFactor).toInt()
        try {
            windowManager.updateViewLayout(floatView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 打开图片选择器 */
    private fun openImagePicker(target: String) {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            Toast.makeText(this, "请选择一张图片作为立绘", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理选择的图片 */
    private fun handleSelectedImage(uri: Uri, target: String) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val dir = File(filesDir, "custom_art")
                if (!dir.exists()) dir.mkdirs()

                val fileName = if (target == "character") "character.png" else "avatar.png"
                val outFile = File(dir, fileName)

                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }

                val path = outFile.absolutePath
                if (target == "character") {
                    CharacterArtLoader.setCharacter(this, path)
                } else {
                    CharacterArtLoader.setAvatar(this, path)
                }

                Toast.makeText(this, "立绘已更新", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 音量减：隐藏整个悬浮UI（物理移除窗口，录屏画面干净，其他内容正常） */
    private fun hideAllUi() {
        if (uiHiddenByVolume) return
        uiHiddenByVolume = true
        runCatching {
            floatView?.let { if (it.parent != null) windowManager.removeView(it) }
            floatView = null
            ballView?.let { if (it.parent != null) windowManager.removeView(it) }
            ballView = null
            dynamicIsland?.hide()
            dynamicIsland = null
        }
    }

    /** 音量加：恢复显示整个悬浮UI */
    private fun showAllUi() {
        if (!uiHiddenByVolume) return
        uiHiddenByVolume = false
        // 若录制隐藏中，不恢复（等录制结束）
        if (uiHiddenByRecording) return
        showFloatBall()
        // 恢复灵动岛
        if (getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
                .getBoolean("dynamic_island", true) && dynamicIsland == null
        ) {
            dynamicIsland = DynamicIsland(this).also { it.show() }
        }
    }

    /** 录屏开始：自动隐藏 UI（物理移除，录制素材干净，绝不黑屏） */
    private fun hideAllUiForRecording() {
        if (uiHiddenByRecording) return
        uiHiddenByRecording = true
        runCatching {
            floatView?.let { if (it.parent != null) windowManager.removeView(it) }
            floatView = null
            ballView?.let { if (it.parent != null) windowManager.removeView(it) }
            ballView = null
            dynamicIsland?.hide()
            dynamicIsland = null
        }
    }

    /** 录屏结束：自动恢复 UI（若音量键隐藏中则保持隐藏） */
    private fun showAllUiForRecording() {
        if (!uiHiddenByRecording) return
        uiHiddenByRecording = false
        if (uiHiddenByVolume) return
        showFloatBall()
        if (getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
                .getBoolean("dynamic_island", true) && dynamicIsland == null
        ) {
            dynamicIsland = DynamicIsland(this).also { it.show() }
        }
    }

    /** 无障碍服务是否已开启 */
    private fun isAccessibilityOn(): Boolean {
        return runCatching {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { it.contains("com.mikasa/.VolumeKeyService") }
        }.getOrDefault(false)
    }

    /** 悬浮窗UI设置弹窗：防录屏（音量键隐藏）+ 无障碍引导 */
    private fun showSettingsDialog() {
        val dlg = android.app.AlertDialog.Builder(this)
        dlg.setTitle("悬浮窗UI设置")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(8))
        }

        // 防录屏开关（自动：检测到录屏 → UI自动隐藏，素材干净）
        val prefs = getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
        val antiSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "防录屏（录屏时自动隐藏UI）"
            isChecked = prefs.getBoolean("anti_record", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("anti_record", checked).apply()
                Toast.makeText(
                    this@FloatingWindowService,
                    if (checked) "防录屏已开启：检测到录屏时自动隐藏UI，录制素材干净" else "防录屏已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        content.addView(antiSwitch)

        // 说明文字
        val hint = TextView(this).apply {
            text = "防录屏（自动）：\n· 检测到系统录屏 → 悬浮UI自动消失，录制画面干净\n· 结束录屏 → UI自动恢复\n· 其他画面（视频/游戏）正常录制，绝不黑屏\n\n" +
                    "音量键控制（手动，独立功能）：\n· 按【音量-】→ 手动隐藏UI\n· 按【音量+】→ 手动恢复\n\n" +
                    "两者都需开启无障碍服务（仅Android 12+支持音量键）。"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, dpToPx(8), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        content.addView(hint)

        // 无障碍状态 + 开启按钮
        val accEnabled = isAccessibilityOn()
        val btnAcc = Button(this).apply {
            text = if (accEnabled) "✅ 无障碍已开启（检测+音量键可用）" else "⚠️ 无障碍未开启，点击前往开启"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    )
                }
            }
        }
        content.addView(btnAcc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(12) })

        dlg.setView(content)
        dlg.setPositiveButton("完成", null)
        dlg.show()
    }

    /** 隐藏面板（属性动画Q弹收起，结束立即回悬浮球，兜底定时器保证一定回来） */
    private fun hideFloatingWindow() {
        if (isPanelAnimating) return
        val view = floatView ?: run {
            showFloatBall()
            return
        }
        isPanelAnimating = true

        // 属性动画：缩放 + 淡出（保证一定播放，结束回调移除）
        view.animate()
            .scaleX(0.7f)
            .scaleY(0.7f)
            .alpha(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                try {
                    if (view.parent != null) windowManager.removeView(view)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (floatView === view) floatView = null
                isPanelAnimating = false
                showFloatBall()
            }
            .start()

        // 兜底：1秒后仍未移除则强制处理（防止动画异常导致悬浮球消失）
        mainHandler.postDelayed({
            if (view.parent != null) {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (floatView === view) floatView = null
                isPanelAnimating = false
                showFloatBall()
            }
        }, 1000L)
    }

    /** 完全停止服务（从主界面停止按钮调用） */
    private fun stopAll() {
        try {
            floatView?.let { windowManager.removeView(it) }
            ballView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        floatView = null
        ballView = null
        stopForeground(true)
        stopSelf()
    }

    /** 应用自定义字体到所有TextView */
    private fun applyCustomFont(view: View?) {
        customTypeface ?: return
        when (view) {
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyCustomFont(view.getChildAt(i))
                }
            }
            is TextView -> view.typeface = customTypeface
            is EditText -> view.typeface = customTypeface
        }
    }

    /** dp转px */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /** 创建通知渠道 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /** 创建前台服务通知 */
    private fun createNotification(): Notification {
        val intent = Intent(this, FloatingWindowService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_content))
            .setSmallIcon(R.drawable.ic_placeholder_character)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        removeGameOverlay()
        runCatching { audioPlayer?.release() }; audioPlayer = null
        stopScreenRecord()
        dynamicIsland?.hide()
        dynamicIsland = null
        runCatching { unregisterReceiver(volumeKeyReceiver) }
        runCatching { unregisterReceiver(sysVolumeReceiver) }
        super.onDestroy()
        try {
            floatView?.let {
                windowManager.removeView(it)
                floatView = null
            }
            ballView?.let {
                windowManager.removeView(it)
                ballView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}