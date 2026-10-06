package com.mikasa.ui.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 音乐常驻前台服务：App 退到后台/被划掉后维持进程不被系统回收，
 * 让 MusicEngine 里的 MediaPlayer 继续播放。本身不持有播放器，只挂一条后台通知 + 保活。
 */
class MusicService : Service() {
    private val NOTIF_ID = 0x5A10

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var channelId: String? = null
        if (Build.VERSION.SDK_INT >= 26) {
            channelId = "xiaoran_music"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "小染音乐", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
        else Notification.Builder(this)
        val notification = builder
            .setContentTitle("小染音乐 · 后台播放中")
            .setContentText("切页/退后台继续播放，回到音乐页可暂停")
            .setSmallIcon(com.mikasa.R.drawable.ic_fab_play)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // 通知渠道缺失等场景忽略，不影响播放
        }
        return START_STICKY
    }
}
