package com.mikasa.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** 下载进度共享：FilesPage 收集以显示进度条/“下载中”。key=文件名，值 0..1。 */
object DownloadHub {
    val progress = MutableStateFlow<Map<String, Double>>(emptyMap())

    @Synchronized
    fun inProgress(name: String, p: Double) { progress.value = progress.value + (name to p) }

    @Synchronized
    fun done(name: String) {
        val m = progress.value.toMutableMap(); m.remove(name); progress.value = m
    }

    fun isRunning(name: String): Boolean = progress.value.containsKey(name)
}

/**
 * 后台下载服务：前台通知带进度条，完成后另弹通知。即使退出软件/切换页面也继续下载。
 * 用法：context.startService(Intent(context, DownloadService::class.java).putExtra("url", url).putExtra("name", name))
 */
class DownloadService : Service() {
    companion object {
        private const val CH_PROG = "xiaoran_dl_progress"
        private const val CH_DONE = "xiaoran_dl_done"
        private const val ID_FOREGROUND = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url")
        val name = intent?.getStringExtra("name") ?: "file"
        if (url.isNullOrBlank()) return START_NOT_STICKY
        ensureChannels()
        startForeground(ID_FOREGROUND, progNotification(name, 0))
        Thread({ runDownload(url, name) }, "dl-$name").start()
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, name: String) {
        val dir = FilesApi.xiaoranDir(this)
        val part = java.io.File(dir, name + ".part")
        val target = java.io.File(dir, name)

        // 已下载（最终文件存在且非空）→ 无需重下（修“重进重下”）
        if (target.exists() && target.length() > 0) {
            DownloadHub.done(name)
            notifyDone(name, true)
            stopForegroundSafely()
            stopSelf()
            return
        }

        val isResume = part.exists() && part.length() > 0
        val resumeFrom = if (isResume) part.length() else 0L

        var total = -1L
        var append = false
        var base = 0L

        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 300000
        if (isResume) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        val code = conn.responseCode
        when (code) {
            206 -> {
                // 续传：total 从 Content-Range: bytes a-b/TOTAL 取
                total = (conn.getHeaderField("Content-Range") ?: "").substringAfter('/').trim().toLongOrNull() ?: -1L
                append = true
                base = resumeFrom
            }
            416 -> {
                // Range 不被满足（.part 已≈完整）→ 直接落盘完成
                conn.disconnect()
                if (part.length() > 0) {
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
                }
                DownloadHub.done(name)
                notifyDone(name, true)
                stopForegroundSafely()
                stopSelf()
                return
            }
            else -> {
                // 200（或未知）：服务端忽略 Range 或全新下载 → 从头写
                total = conn.contentLength.toLong()
                if (total <= 0) total = headLength(url)   // chunked/无长度 → HEAD 兜底
                append = false
                base = 0
                if (isResume) part.delete()               // 服务端不支持 Range，清掉旧 .part 从头下
            }
        }

        var ok = false
        try {
            // 首帧立即注册“下载中”，避免一直 0%；total 未知用哨兵 -1.0（UI 显示不定进度条）
            DownloadHub.inProgress(name, if (total > 0) 0.0 else -1.0)
            notifyProgress(name, if (total > 0) 0.0 else -1.0)   // total 未知 → 不定进度

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var read = input.read(buf)
                    var sum = 0L
                    while (read != -1) {
                        out.write(buf, 0, read); sum += read
                        if (total > 0) {
                            val p = ((base + sum).toDouble() / total).coerceIn(0.0, 0.999)
                            DownloadHub.inProgress(name, p)
                            notifyProgress(name, p)
                        }
                        read = input.read(buf)
                    }
                }
            }
            conn.disconnect()

            if (part.length() <= 0) throw RuntimeException("empty file")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }

            // 文件管理器可见（MediaStore → 手机「下载/小染注入」，API 29+）
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/小染注入")
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            target.inputStream().use { ins -> ins.copyTo(os) }
                        }
                    }
                } catch (e: Exception) { }
            }
            ok = true
        } catch (e: Exception) {
            ok = false
            // 保留 .part，下次可续传
        }
        DownloadHub.done(name)
        notifyDone(name, ok)
        stopForegroundSafely()
        stopSelf()
    }

    /** HEAD 兜底取 Content-Length（chunked/无长度时尽量拿到 total）。失败/不支持返回 -1。 */
    private fun headLength(url: String): Long = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.responseCode
        val l = c.contentLength.toLong()
        c.disconnect()
        l
    } catch (e: Exception) {
        -1L
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_PROG) == null)
                nm.createNotificationChannel(NotificationChannel(CH_PROG, "下载", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel(CH_DONE) == null)
                nm.createNotificationChannel(NotificationChannel(CH_DONE, "下载完成", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun progNotification(name: String, pct: Int): Notification =
        NotificationCompat.Builder(this, CH_PROG)
            .setSmallIcon(com.mikasa.R.mipmap.ic_launcher)
            .setContentTitle("正在下载")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, (pct * 10).coerceIn(0, 1000), false)
            .build()

    private fun notifyProgress(name: String, p: Double) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (p < 0) {
            // total 未知 → 不定进度（转圈）
            nm.notify(ID_FOREGROUND, NotificationCompat.Builder(this, CH_PROG)
                .setSmallIcon(com.mikasa.R.mipmap.ic_launcher)
                .setContentTitle("正在下载").setContentText(name)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setIndeterminateProgress()
                .build())
        } else {
            nm.notify(ID_FOREGROUND, progNotification(name, (p * 100).toInt()))
        }
    }

    private fun stopForegroundSafely() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun doneNotification(name: String, ok: Boolean): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(com.mikasa.R.mipmap.ic_launcher)
            .setContentTitle(if (ok) "下载完成" else "下载失败")
            .setContentText(if (ok) "「$name」已保存到 手机「下载/小染注入」" else "「$name」下载失败，请到文件页重试")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun notifyDone(name: String, ok: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(name.hashCode(), doneNotification(name, ok))
    }
}
