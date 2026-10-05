package com.xiaoran.nb.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.xiaoran.nb.R
import com.xiaoran.nb.ui.launcher.MusicState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 高仿 iOS 灵动岛
 * - 正宗胶囊形常驻顶部居中：时间(白) · 歌名(播放时) · 帧率(上)/温度(下) 米白
 * - 点击 → 3D 展开：全屏遮罩压暗两侧 + 卡片绕顶轴 3D 立起(rotateX + scaleY + 透视) 弹簧回弹
 * - 点击空白 → 3D 弹回收缩回胶囊
 * - 动画平滑度(设置页)：0 流畅 / 1 均衡 / 2 华丽，控制时长与回弹强度
 * - 帧率: Choreographer 真实统计(≤240) · 温度: 电池广播真实读取
 */
class DynamicIsland(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var cardView: View? = null
    private var expanded = false
    private var animating = false

    // 帧率统计
    private var fps = 0
    private var frameCount = 0L
    private var lastFpsTime = 0L

    // 温度/电量
    private var temperature = "--"
    private var battery = "--"

    private fun smooth(): Int = prefs.getInt("di_anim_smooth", 1)

    private fun expandDur(): Long = when (smooth()) { 0 -> 300L; 1 -> 380L; else -> 520L }
    private fun collapseDur(): Long = when (smooth()) { 0 -> 220L; 1 -> 280L; else -> 380L }
    private fun overshoot(): Float = when (smooth()) { 0 -> 1.2f; 1 -> 1.5f; else -> 2.0f }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updatePill()
            updateCard()
            handler.postDelayed(this, 500)
        }
    }

    fun show() {
        if (pillView != null) return
        val inflater = LayoutInflater.from(context)
        pillView = inflater.inflate(R.layout.view_dynamic_island, null)
        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(36),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(6)
        }
        pillView?.setOnClickListener { toggle() }
        try {
            wm.addView(pillView, pillParams)
            startChoreographer()
            registerBattery()
            handler.post(refreshRunnable)
        } catch (e: Exception) {
            android.util.Log.e("DynamicIsland", "show addView fail", e)
        }
    }

    fun hide() {
        collapse()
        handler.removeCallbacksAndMessages(null)
        unregisterBattery()
        stopChoreographer()
        pillView?.let { runCatching { wm.removeView(it) } }
        pillView = null
        pillParams = null
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
    }

    private fun toggle() {
        if (animating) return
        if (expanded) collapse() else expand()
    }

    // ── 3D 展开 ──
    private fun expand() {
        if (expanded || animating) return
        expanded = true
        animating = true

        val inflater = LayoutInflater.from(context)
        val overlay = inflater.inflate(R.layout.view_dynamic_island_card, null)
        val card = overlay.findViewById<View>(R.id.di_card)
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        overlayView = overlay
        cardView = card
        try {
            wm.addView(overlay, overlayParams)
        } catch (e: Exception) {
            android.util.Log.e("DynamicIsland", "expand overlay addView fail", e)
            expanded = false
            animating = false
            return
        }
        overlay.setOnClickListener { collapse() }
        updateCard()

        // 3D 透视：加大景深，让立起效果更真实
        card.cameraDistance = dp(2400).toFloat()
        card.pivotY = 0f
        card.pivotX = card.width / 2f
        card.rotationX = -62f
        card.scaleY = 0.16f
        card.alpha = 0f
        overlay.alpha = 0f

        // 遮罩先淡入 → 卡片绕顶轴 3D 立起(rotateX) + 纵向拉伸(scaleY)，弹簧回弹
        overlay.animate().alpha(1f).setDuration(collapseDur()).setInterpolator(AccelerateDecelerateInterpolator()).start()
        card.animate()
            .rotationX(0f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(expandDur())
            .setInterpolator(OvershootInterpolator(overshoot()))
            .withEndAction { animating = false }
            .start()
    }

    // ── 3D 收起 ──
    private fun collapse() {
        val overlay = overlayView ?: return
        if (animating && !expanded) return
        if (!expanded) return
        expanded = false
        animating = true

        val card = cardView ?: overlay
        val ov = overlay
        card.animate()
            .rotationX(-62f)
            .scaleY(0.16f)
            .alpha(0f)
            .setDuration(collapseDur())
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                ov.alpha = 0f
                runCatching { wm.removeView(ov) }
                overlayView = null
                cardView = null
                animating = false
                // 胶囊恢复呼吸感
                pillView?.alpha = 1f
                pillView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            }
            .start()
        ov.animate().alpha(0f).setDuration(collapseDur() * 2 / 3).start()
    }

    // ── 数据刷新 ──
    private fun updatePill() {
        val v = pillView ?: return
        v.findViewById<TextView>(R.id.tv_di_time)?.text = nowTime()
        val title = MusicState.songName.ifBlank { null }
        val tvTitle = v.findViewById<TextView>(R.id.tv_di_title)
        if (title != null) {
            tvTitle?.text = title
            tvTitle?.visibility = View.VISIBLE
        } else {
            tvTitle?.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.tv_di_fps)?.text = "${fps}Hz"
        v.findViewById<TextView>(R.id.tv_di_temp)?.text = temperature
    }

    private fun updateCard() {
        val card = cardView ?: return
        card.findViewById<TextView>(R.id.tv_di_card_time)?.text = nowTime()
        card.findViewById<TextView>(R.id.tv_di_card_date)?.text = nowDate()
        val title = MusicState.songName.ifBlank { null }
        card.findViewById<TextView>(R.id.tv_di_card_music)?.text = title ?: "未在播放音乐"
        card.findViewById<TextView>(R.id.tv_di_card_fps)?.text = "${fps}"
        card.findViewById<TextView>(R.id.tv_di_card_temp)?.text = temperature
        card.findViewById<TextView>(R.id.tv_di_card_battery)?.text = battery
    }

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun nowDate(): String =
        SimpleDateFormat("EEE M月d日", Locale.getDefault()).format(Date())

    // ── 真实帧率 ──
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    private fun startChoreographer() {
        stopChoreographer()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = System.nanoTime()
                if (lastFpsTime == 0L) lastFpsTime = now
                frameCount++
                val span = now - lastFpsTime
                if (span >= 1_000_000_000L) {
                    val f = (frameCount * 1_000_000_000L / span).toInt()
                    fps = f.coerceIn(0, 240)
                    frameCount = 0
                    lastFpsTime = now
                    handler.post { updatePill(); updateCard() }
                }
                choreographer?.postFrameCallback(this)
            }
        }
        frameCallback = cb
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(cb)
    }

    private fun stopChoreographer() {
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        frameCallback = null
        choreographer = null
    }

    // ── 真实温度/电量 ──
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { t ->
                if (t != -1) temperature = String.format(Locale.US, "%.1f℃", t / 10.0)
            }
            val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l != null && l != -1 && s != null && s > 0) battery = "${l * 100 / s}%"
        }
    }

    private fun registerBattery() {
        runCatching {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun unregisterBattery() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
