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
        var ok = false
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 300000
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = input.read(buf)
                    var sum = 0L
                    while (read != -1) {
                        out.write(buf, 0, read); sum += read
                        if (total > 0) {
                            val p = (sum.toDouble() / total).coerceIn(0.0, 0.999)
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
        }
        DownloadHub.done(name)
        notifyDone(name, ok)
        // 移除前台进度通知，保留完成通知；稍后自停
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        nm.notify(ID_FOREGROUND, progNotification(name, (p * 100).toInt()))
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
