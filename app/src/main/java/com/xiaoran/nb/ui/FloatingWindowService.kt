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
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.xiaoran.nb.R
import com.xiaoran.nb.imgui.ImguiHost
import com.xiaoran.nb.ui.launcher.MusicState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 悬浮窗服务 —— 小染自动注入
 * 单个 GLSurfaceView 系统悬浮窗（imgui-md3 的 MD3 ImGui 引擎渲染），合并原
 * 悬浮球 / 功能面板 / 灵动岛 三个窗口：收起态=小圆（≈球），展开态=面板（含
 * imgui-md3 原 UI + 新增"小染"功能页），灵动岛信息并入功能页顶部状态条。
 * 保留：前台通知、悬浮权限、音量键隐藏/显示、录屏自动隐藏、位置持久化、自制 toast。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xiaoran_float_channel"

        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(context) else true

        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var host: ImguiHost? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var collapsed = false

    private var savedX = Int.MIN_VALUE
    private var savedY = 0
    private var savedCollapsed = false

    private var uiHiddenByVolume = false
    private var uiHiddenByRecording = false

    private var temperature = ""
    private var battery = ""
    private var fps = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() { pushStatus(); mainHandler.postDelayed(this, 1000L) }
    }

    private var frameCount = 0L
    private var lastFpsTime = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = frameTimeNanos
            if (lastFpsTime == 0L) lastFpsTime = now
            frameCount++
            if (now - lastFpsTime >= 1_000_000_000L) {
                fps = (frameCount * 1_000_000_000L / (now - lastFpsTime)).toInt().coerceIn(0, 240)
                frameCount = 0
                lastFpsTime = now
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val volumeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.xiaoran.nb.VOLUME_HIDE" -> hideAllUi()
                "com.xiaoran.nb.VOLUME_SHOW" -> showAllUi()
                "com.xiaoran.nb.RECORDING_ON" -> if (
                        getSharedPreferences("xiaoran_prefs", MODE_PRIVATE)
                            .getBoolean("anti_record", false)) hideAllUiForRecording()
                "com.xiaoran.nb.RECORDING_OFF" -> showAllUiForRecording()
            }
        }
    }

    private val sysVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            val nv = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val pv = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (nv < 0 || pv < 0) return
            if (nv < pv) hideAllUi() else if (nv > pv) showAllUi()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (t != null && t != -1) temperature = String.format(Locale.US, "%.1f℃", t / 10.0)
            val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l != null && l != -1 && s != null && s > 0) battery = "${l * 100 / s}%"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadSavedState()
        createNotificationChannel()
        runCatching {
            registerReceiver(volumeKeyReceiver, IntentFilter("com.xiaoran.nb.VOLUME_HIDE").apply {
                addAction("com.xiaoran.nb.VOLUME_SHOW")
                addAction("com.xiaoran.nb.RECORDING_ON")
                addAction("com.xiaoran.nb.RECORDING_OFF")
            })
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(sysVolumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    Context.RECEIVER_EXPORTED)
            else
                registerReceiver(sysVolumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        }
        runCatching { registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    }

    private var initX = 0; private var initY = 0
    private var initRX = 0f; private var initRY = 0f
    private var dragging = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForeground(NOTIFICATION_ID, createNotification()) } catch (e: Exception) { e.printStackTrace() }
        if (!canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            requestOverlayPermission(this)
            return START_STICKY
        }
        collapsed = savedCollapsed
        ensureOverlay()
        host?.pushFunctions(buildXrFunctionsProtocol())
        bootstrapGate()
        mainHandler.removeCallbacks(statusTicker)
        mainHandler.post(statusTicker)
        runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
        return START_STICKY
    }

    private fun ensureOverlay() {
        if (overlayView != null) return
        val h = ImguiHost(this)
        host = h
        h.resetGate()   // 每次（重）开悬浮窗都从启动页/卡密重进，不自动登录
        h.setToggleListener { name, checked ->
            showFloatToast("${if (checked) "已开启" else "已关闭"} · $name")
        }
        overlayView = h.getView()
        applyOverlayParams()
        overlayView?.setOnTouchListener { _, e -> onTouch(e) }
        try {
            overlayView?.let { v ->
                windowManager.addView(v, overlayParams)
                h.start()
                // 快开：快速放大+淡入（~140ms）
                v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "悬浮窗启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyOverlayParams() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val (w, hgt) = overlaySize()
        overlayParams = WindowManager.LayoutParams(
            w, hgt, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX != Int.MIN_VALUE) savedX else dpToPx(20)
            y = if (savedX != Int.MIN_VALUE) savedY else dpToPx(80)
        }
    }

    private fun overlaySize(): Pair<Int, Int> {
        if (collapsed) { val s = dpToPx(112); return s to s }
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val designW = dpToPx(700); val designH = dpToPx(420)
        val w = minOf((screenW * 0.85f).toInt(), designW)
        val hgt = (w * designH / designW).coerceAtMost((screenH * 0.8f).toInt()).coerceAtLeast(designH)
        return w to hgt
    }

    private fun onTouch(e: MotionEvent): Boolean {
        val lp = overlayParams ?: return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                initX = lp.x; initY = lp.y; initRX = e.rawX; initRY = e.rawY; dragging = false
                host?.forwardTouch(0, e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - initRX; val dy = e.rawY - initRY
                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) dragging = true
                if (dragging) {
                    lp.x = (initX + dx).toInt(); lp.y = (initY + dy).toInt()
                    runCatching { windowManager.updateViewLayout(overlayView, lp) }
                }
                host?.forwardTouch(2, e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                host?.forwardTouch(1, e.x, e.y)
                if (!dragging) {
                    collapsed = !collapsed
                    applyOverlayParams()
                    runCatching { windowManager.updateViewLayout(overlayView, overlayParams) }
                    saveState()
                }
            }
        }
        return true
    }

    /** 开悬浮窗：预填上次卡密 + 拉后端服务状态（停用/公告/强制更新）推给 C++ 门禁页 */
    private fun bootstrapGate() {
        host?.let { h ->
            val last = getSharedPreferences("xiaoran_prefs", MODE_PRIVATE).getString("last_card", "")
            h.preFillCard(last ?: "")
            Thread {
                try {
                    val s = com.xiaoran.nb.net.XrApi.status()
                    val ann = buildString {
                        if (s.announceTitle.isNotEmpty()) append(s.announceTitle)
                        if (s.announceContent.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(s.announceContent) }
                    }
                    h.pushService(s.serviceDisabled, ann, s.update.force, s.update.minVersion, s.update.url)
                } catch (e: Exception) {
                    h.pushService(false, "", false, "", "")
                }
                try {
                    fun csv(items: List<com.xiaoran.nb.net.XrApi.FileItem>) =
                        items.joinToString("\n") { "${it.name}\t${it.url}" }
                    h.pushMedia(csv(com.xiaoran.nb.net.XrApi.files()),
                                csv(com.xiaoran.nb.net.XrApi.music()))
                } catch (e: Exception) { h.pushMedia("", "") }
                try {
                    val st = com.xiaoran.nb.net.XrApi.stats()
                    h.pushStats(st.online, st.total)
                } catch (e: Exception) { h.pushStats(0, 0) }
                try {
                    val qa = com.xiaoran.nb.net.XrApi.csQa()
                    h.pushCs(qa.joinToString("\n") { "${it.q}\t${it.a}" })
                } catch (e: Exception) { h.pushCs("") }
            }.start()
        }
    }

    private fun pushStatus() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val song = MusicState.songName
        val sb = StringBuilder("time $time\n")
        if (fps > 0) sb.append("fps ").append(fps).append("\n")
        if (temperature.isNotEmpty()) sb.append("temp ").append(temperature).append("\n")
        if (battery.isNotEmpty()) sb.append("battery ").append(battery).append("\n")
        if (song.isNotEmpty()) sb.append("song ").append(song).append("\n")
        host?.pushStatus(sb.toString())
    }

    /** 复刻原 buildTabData：把 5 个导航页的功能列表转成行协议推给 C++ */
    private fun buildXrFunctionsProtocol(): String {
        val s = StringBuilder()
        fun nav(label: String) { s.append("N ").append(label).append("\n") }
        fun tab(label: String) { s.append("T ").append(label).append("\n") }
        fun item(name: String) { s.append("I ").append(name).append(" 0\n") }
        fun group(name: String, open: Boolean) { s.append("G ").append(name).append(if (open) " 1" else " 0").append("\n") }
        fun child(name: String) { s.append("C ").append(name).append(" 0\n") }
        fun sw(name: String) { s.append("S ").append(name).append(" 0\n") }

        nav(getString(R.string.nav_home)); tab(getString(R.string.tab_home))
        group("折叠示例", true); child("功能一"); child("功能二"); child("功能三")
        sw("按钮示例"); item("欢迎使用小染自动注入"); item("选择左侧菜单开始")

        nav(getString(R.string.nav_person))
        tab(getString(R.string.tab_person_beauty)); listOf("冰炫幻影","螺旋心影","诡哈幽影","献出心脏","刷新状态","投降","霓虹天后").forEach { item(it) }
        tab(getString(R.string.tab_item_mix)); listOf("帽子混搭","服装组合","配饰搭配","颜色调整").forEach { item(it) }
        tab(getString(R.string.tab_action)); listOf("舞蹈动作","待机姿势","移动姿态","特殊动作").forEach { item(it) }

        nav(getString(R.string.nav_gun))
        tab(getString(R.string.tab_primary)); listOf("AK47皮肤","M4A1皮肤","AWP皮肤","特效换肤").forEach { item(it) }
        tab(getString(R.string.tab_secondary)); listOf("手枪皮肤","刀具皮肤").forEach { item(it) }
        tab(getString(R.string.tab_melee)); listOf("斧头皮肤","棍棒皮肤").forEach { item(it) }

        nav(getString(R.string.nav_misc))
        tab(getString(R.string.tab_effect)); listOf("击杀特效","枪口火焰","弹痕效果").forEach { item(it) }
        tab(getString(R.string.tab_sound)); listOf("击杀音效","背景音乐","语音包").forEach { item(it) }
        tab(getString(R.string.tab_other)); listOf("准星定制","界面美化","其他功能").forEach { item(it) }

        nav(getString(R.string.nav_settings))
        tab(getString(R.string.tab_general)); listOf("自动更新","性能优化","通知设置").forEach { item(it) }
        tab(getString(R.string.tab_about)); listOf("版本信息","开发者","开源协议").forEach { item(it) }
        return s.toString()
    }

    private fun loadSavedState() {
        runCatching {
            val p = getSharedPreferences("xiaoran_float", MODE_PRIVATE)
            savedX = p.getInt("x", Int.MIN_VALUE); savedY = p.getInt("y", 0)
            savedCollapsed = p.getBoolean("collapsed", false)
        }
    }

    private fun saveState() {
        runCatching {
            overlayParams?.let { savedX = it.x; savedY = it.y }
            getSharedPreferences("xiaoran_float", MODE_PRIVATE).edit()
                .putInt("x", savedX).putInt("y", savedY).putBoolean("collapsed", collapsed).apply()
        }
    }

    private fun hideAllUi() { if (uiHiddenByVolume) return; uiHiddenByVolume = true; removeOverlay() }
    private fun showAllUi() {
        if (!uiHiddenByVolume) return; uiHiddenByVolume = false
        if (uiHiddenByRecording) return
        if (overlayView == null) { ensureOverlay(); host?.pushFunctions(buildXrFunctionsProtocol()) }
    }
    private fun hideAllUiForRecording() { if (uiHiddenByRecording) return; uiHiddenByRecording = true; removeOverlay() }
    private fun showAllUiForRecording() {
        if (!uiHiddenByRecording) return; uiHiddenByRecording = false
        if (uiHiddenByVolume) return
        if (overlayView == null) { ensureOverlay(); host?.pushFunctions(buildXrFunctionsProtocol()) }
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            // 快关：快速缩小+淡出（~120ms），结束再移除
            v.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(120L)
                .withEndAction {
                    runCatching { if (v.parent != null) windowManager.removeView(v) }
                    runCatching { host?.stop() }
                }.start()
        }
        overlayView = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statusTicker)
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        runCatching { unregisterReceiver(volumeKeyReceiver) }
        runCatching { unregisterReceiver(sysVolumeReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        removeOverlay(); host = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_desc); setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getService(this, 0, Intent(this, javaClass),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_content))
            .setSmallIcon(R.drawable.ic_placeholder_character)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun showFloatToast(message: String) {
        try {
            val toastView = LayoutInflater.from(this).inflate(R.layout.view_float_toast, null)
            toastView.findViewById<TextView>(R.id.tv_toast_text).text = message
            toastView.findViewById<ImageView>(R.id.iv_toast_icon).setImageResource(R.drawable.ic_check_white)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dpToPx(150) }
            windowManager.addView(toastView, p)
            toastView.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.toast_pop_in))
            mainHandler.postDelayed({
                runCatching { toastView.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.toast_pop_out)) }
                mainHandler.postDelayed({ runCatching { windowManager.removeView(toastView) } }, 300L)
            }, 1500L)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
