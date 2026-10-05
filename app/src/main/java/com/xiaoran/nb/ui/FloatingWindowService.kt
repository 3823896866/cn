package com.xiaoran.nb.ui

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
import com.xiaoran.nb.ui.view.GifMovieView
import com.xiaoran.nb.R
import com.xiaoran.nb.ui.adapter.FunctionAdapter
import com.xiaoran.nb.ui.adapter.NavAdapter
import com.xiaoran.nb.ui.util.CharacterArtLoader
import com.xiaoran.nb.ui.view.CircleImageView
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

    // 防录屏：音量键隐藏/显示 UI
    private var uiHiddenByVolume = false

    // 防录屏：录屏自动隐藏（检测到录屏时物理移除，录完自动恢复）
    private var uiHiddenByRecording = false

    /** 广播接收器：音量键控制 + 录屏自动检测（无障碍服务发送） */
    private val volumeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.xiaoran.nb.VOLUME_HIDE" -> hideAllUi()
                "com.xiaoran.nb.VOLUME_SHOW" -> showAllUi()
                // 录屏开始：防录屏开关开启时自动隐藏 UI（素材干净，绝不黑屏）
                "com.xiaoran.nb.RECORDING_ON" -> {
                    if (getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
                            .getBoolean("anti_record", false)
                    ) {
                        hideAllUiForRecording()
                    }
                }
                // 录屏结束：自动恢复
                "com.xiaoran.nb.RECORDING_OFF" -> showAllUiForRecording()
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
                IntentFilter("com.xiaoran.nb.VOLUME_HIDE").apply {
                    addAction("com.xiaoran.nb.VOLUME_SHOW")
                    addAction("com.xiaoran.nb.RECORDING_ON")
                    addAction("com.xiaoran.nb.RECORDING_OFF")
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

        // 灵动岛（设置里可关闭）
        val diEnabled = getSharedPreferences("mikasa_prefs", MODE_PRIVATE)
            .getBoolean("dynamic_island", true)
        if (diEnabled && dynamicIsland == null) {
            dynamicIsland = DynamicIsland(this).also { it.show() }
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

        // Compose 液态玻璃面板（替代原 XML 面板 view_floating_panel）
        val composeView = androidx.compose.ui.platform.AndroidComposeView(this)
        composeView.setContent {
            com.xiaoran.nb.ui.theme.MikasaTheme {
                com.xiaoran.nb.ui.glass.GlassFloatingPanel(
                    onClose = { hideFloatingWindow() },
                    onZoomIn = { zoomPanel(1.15f) },
                    onZoomOut = { zoomPanel(0.87f) },
                    onOpenSettings = { showSettingsDialog() },
                    onFloatToast = { msg -> showFloatToast(msg) }
                )
            }
        }
        floatView = composeView

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

        // 设置拖动（长按空白区域拖动整个面板）
        setupDragSupport()

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

            // 设置按钮（悬浮窗UI设置：防录屏开关 + 音量键控制）
            view.findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
                showSettingsDialog()
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

            // 搜索实时过滤
            view.findViewById<EditText>(R.id.et_search).addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val adapter = tabPagerAdapter?.getAdapter(viewPager?.currentItem ?: 0)
                    adapter?.filter(s?.toString() ?: "")
                }
            })

            // 搜索框点击弹出键盘（悬浮窗默认NOT_FOCUSABLE，需临时切换焦点模式）
            val etSearch = view.findViewById<EditText>(R.id.et_search)
            etSearch.setOnFocusChangeListener { _, hasFocus ->
                val lp = layoutParams ?: return@setOnFocusChangeListener
                try {
                    if (hasFocus) {
                        // 允许获得焦点 → 弹出键盘
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    } else {
                        // 恢复不抢焦点
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    }
                    windowManager.updateViewLayout(floatView, lp)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            etSearch.setOnClickListener {
                etSearch.requestFocus()
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
            loadNavContent(position)
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
            rv.layoutManager = GridLayoutManager(this@FloatingWindowService, 2)
            while (adapters.size <= position) adapters.add(FunctionAdapter())
            val adapter = adapters[position]
            adapter.setItems(pages[position])
            // 开关切换 → 自定义消息通知
            adapter.onToggle = { name, checked ->
                showFloatToast("${if (checked) "已开启" else "已关闭"} · $name")
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

    /** 根据导航项生成 Tab 与页面数据 */
    private fun buildTabData(navPosition: Int): Pair<List<String>, List<List<FunctionAdapter.FunctionItem>>> {
        val tabs = when (navPosition) {
            0 -> listOf(getString(R.string.tab_home))
            1 -> listOf(
                getString(R.string.tab_person_beauty),
                getString(R.string.tab_item_mix),
                getString(R.string.tab_action)
            )
            2 -> listOf(
                getString(R.string.tab_primary),
                getString(R.string.tab_secondary),
                getString(R.string.tab_melee)
            )
            3 -> listOf(
                getString(R.string.tab_effect),
                getString(R.string.tab_sound),
                getString(R.string.tab_other)
            )
            4 -> listOf(
                getString(R.string.tab_general),
                getString(R.string.tab_about)
            )
            else -> listOf(getString(R.string.tab_home))
        }

        val pages = tabs.map { tabName ->
            when (tabName) {
                getString(R.string.tab_person_beauty) -> listOf(
                    FunctionAdapter.FunctionItem("冰炫幻影"),
                    FunctionAdapter.FunctionItem("螺旋心影"),
                    FunctionAdapter.FunctionItem("诡哈幽影"),
                    FunctionAdapter.FunctionItem("献出心脏"),
                    FunctionAdapter.FunctionItem("刷新状态"),
                    FunctionAdapter.FunctionItem("投降"),
                    FunctionAdapter.FunctionItem("霓虹天后")
                )
                getString(R.string.tab_item_mix) -> listOf(
                    FunctionAdapter.FunctionItem("帽子混搭"),
                    FunctionAdapter.FunctionItem("服装组合"),
                    FunctionAdapter.FunctionItem("配饰搭配"),
                    FunctionAdapter.FunctionItem("颜色调整")
                )
                getString(R.string.tab_action) -> listOf(
                    FunctionAdapter.FunctionItem("舞蹈动作"),
                    FunctionAdapter.FunctionItem("待机姿势"),
                    FunctionAdapter.FunctionItem("移动姿态"),
                    FunctionAdapter.FunctionItem("特殊动作")
                )
                getString(R.string.tab_primary) -> listOf(
                    FunctionAdapter.FunctionItem("AK47皮肤"),
                    FunctionAdapter.FunctionItem("M4A1皮肤"),
                    FunctionAdapter.FunctionItem("AWP皮肤"),
                    FunctionAdapter.FunctionItem("特效换肤")
                )
                getString(R.string.tab_secondary) -> listOf(
                    FunctionAdapter.FunctionItem("手枪皮肤"),
                    FunctionAdapter.FunctionItem("刀具皮肤")
                )
                getString(R.string.tab_melee) -> listOf(
                    FunctionAdapter.FunctionItem("斧头皮肤"),
                    FunctionAdapter.FunctionItem("棍棒皮肤")
                )
                getString(R.string.tab_effect) -> listOf(
                    FunctionAdapter.FunctionItem("击杀特效"),
                    FunctionAdapter.FunctionItem("枪口火焰"),
                    FunctionAdapter.FunctionItem("弹痕效果")
                )
                getString(R.string.tab_sound) -> listOf(
                    FunctionAdapter.FunctionItem("击杀音效"),
                    FunctionAdapter.FunctionItem("背景音乐"),
                    FunctionAdapter.FunctionItem("语音包")
                )
                getString(R.string.tab_other) -> listOf(
                    FunctionAdapter.FunctionItem("准星定制"),
                    FunctionAdapter.FunctionItem("界面美化"),
                    FunctionAdapter.FunctionItem("其他功能")
                )
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
                    FunctionAdapter.FunctionItem("欢迎使用三笠美化"),
                    FunctionAdapter.FunctionItem("选择左侧菜单开始")
                )
            }
        }
        return tabs to pages
    }

    /** 根据导航项加载对应内容 */
    private fun loadNavContent(navPosition: Int) {
        currentNavPosition = navPosition
        currentTabIndex = 0
        val (tabs, pages) = buildTabData(navPosition)
        tabPagerAdapter?.setPages(pages)
        updateTabViews(tabs)
        viewPager?.setCurrentItem(0, false)
        // 清空搜索框，恢复完整列表
        floatView?.findViewById<EditText>(R.id.et_search)?.setText("")
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
            enabled.split(':').any { it.contains("com.xiaoran.nb/.VolumeKeyService") }
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