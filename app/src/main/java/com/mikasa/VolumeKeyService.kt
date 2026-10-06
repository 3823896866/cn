package com.mikasa

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍服务（需在系统无障碍中手动开启）
 *
 * 1. 音量键控制（独立功能）：
 *    - 音量减 = 隐藏悬浮UI（发 VOLUME_HIDE）
 *    - 音量加 = 恢复显示（发 VOLUME_SHOW）
 *
 * 2. 防录屏自动检测（独立功能）：
 *    - 监听系统通知：出现「屏幕录制中」常驻通知 → 发 RECORDING_ON
 *    - 扫描窗口树：状态栏出现录制标识 → 发 RECORDING_ON
 *    - 消失 → 发 RECORDING_OFF
 *    - 悬浮窗服务收到后自动物理移除UI，录屏素材干净，绝不黑屏
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "VolumeKey"
        const val ACTION_HIDE = "com.mikasa.VOLUME_HIDE"
        const val ACTION_SHOW = "com.mikasa.VOLUME_SHOW"
        const val ACTION_REC_ON = "com.mikasa.RECORDING_ON"
        const val ACTION_REC_OFF = "com.mikasa.RECORDING_OFF"

        private val RECORD_KEYWORDS = arrayOf(
            "录制", "录屏", "record", "RECORD",
            "屏幕录制", "正在录制", "屏幕已录制",
            "Screen Record", "screen record"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recording = false
    private var lastNotifyTime = 0L

    // ═══════════════ 1. 音量键控制 ═══════════════
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return super.onKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    runCatching { sendBroadcast(Intent(ACTION_HIDE)) }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    runCatching { sendBroadcast(Intent(ACTION_SHOW)) }
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }

    // ═══════════════ 2. 录屏检测 ═══════════════
    private val scanRunnable = object : Runnable {
        override fun run() {
            checkRecording()
            handler.postDelayed(this, 1500L)
        }
    }

    private fun checkRecording() {
        val found = runCatching { scanWindows() }.getOrDefault(false)
        if (found != recording) {
            recording = found
            runCatching {
                sendBroadcast(Intent(if (found) ACTION_REC_ON else ACTION_REC_OFF))
            }
            Log.d(TAG, "recording=${found}")
        }
    }

    /** 扫描窗口树，搜索状态栏/系统窗口中的录制标识 */
    private fun scanWindows(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
        val windows = windows ?: return false
        // 第一轮：只扫系统小窗口（状态栏/通知/无障碍层），快速命中
        for (window in windows) {
            val type = window.type
            val isSys = type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            val rect = Rect()
            window.getBoundsInScreen(rect)
            if (isSys || (rect.height() in 1..400 && rect.width() < 1500)) {
                if (searchNode(window.root, 0)) return true
            }
        }
        // 第二轮：浅扫所有窗口
        for (window in windows) {
            if (searchNode(window.root, 0)) return true
        }
        return false
    }

    private fun searchNode(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 6) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val haystack = "$text|$desc"
        if (haystack.isNotEmpty()) {
            for (kw in RECORD_KEYWORDS) {
                if (haystack.contains(kw)) {
                    // 过滤自身App的文本，避免误判
                    if (!haystack.contains("mikasa") && !haystack.contains("三笠")) {
                        Log.d(TAG, "hit: $haystack")
                        return true
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (searchNode(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "connected")
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 1000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            // 通知变化：录屏常驻通知出现/消失
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotificationEvent(event)
            }
            // 窗口变化：快速触发一次检测
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (!recording) {
                    handler.removeCallbacks(scanRunnable)
                    handler.postDelayed(scanRunnable, 300L)
                }
            }
        }
    }

    /** 从通知事件中提取文本，识别录屏通知 */
    private fun handleNotificationEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 800) return // 防抖
        lastNotifyTime = now

        // 方式1：事件文本
        val texts = StringBuilder()
        for (i in 0 until event.text.size) {
            texts.append(event.text[i]).append("|")
        }
        // 方式2：ParcelableData = Notification 对象
        var notifText = ""
        try {
            val data = event.parcelableData
            if (data is Notification) {
                notifText = data.tickerText?.toString() ?: ""
                val extras = data.extras
                if (extras != null) {
                    notifText += "|" + (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "")
                    notifText += "|" + (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
                    notifText += "|" + (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "")
                }
            }
        } catch (_: Exception) {}

        val haystack = texts.toString() + "|" + notifText
        if (haystack.isEmpty()) return

        val found = RECORD_KEYWORDS.any { haystack.contains(it) }
        // 通知移除事件（text为空但事件来自通知）→ 视为录制结束
        val isNotification = event.className?.toString()?.contains("Notification") == true

        if (found != recording) {
            recording = found
            runCatching {
                sendBroadcast(Intent(if (found) ACTION_REC_ON else ACTION_REC_OFF))
            }
            Log.d(TAG, "notify recording=${found} :: $haystack")
        } else if (isNotification && !found && recording) {
            // 通知被移除（无文本）→ 录制结束
            recording = false
            runCatching { sendBroadcast(Intent(ACTION_REC_OFF)) }
            Log.d(TAG, "notify removed -> recording=false")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}