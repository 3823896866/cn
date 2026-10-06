package com.mikasa.ui.launcher

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.mutableStateOf

/**
 * 常驻音乐引擎：MediaPlayer 属于进程级单例，不随音乐页销毁而释放。
 * 因此切换页面/重进软件不会停止/重播；只有手动暂停才停。
 * 配合前台服务 MusicService，退出软件(后台)也继续播放。
 */
object MusicEngine {
    val hotSongs = listOf("把回忆拼好给你", "小美满", "孤勇者", "起风了", "光年之外", "漠河舞厅")

    private val mp = MediaPlayer()
    private var ctx: Context? = null
    private var hotMode = false
    private var currentName = ""
    private var currentArtist = ""

    private val _playing = mutableStateOf(false)
    val playing: Boolean get() = _playing.value
    private val _loading = mutableStateOf(false)
    val loading: Boolean get() = _loading.value
    private val _current = mutableStateOf<MusicApi.Song?>(null)
    val current: MusicApi.Song? get() = _current.value
    var hotIndex = 0

    fun init(context: Context) {
        if (ctx == null) {
            ctx = context.applicationContext
            attachListeners()
        }
    }

    private var attached = false
    private fun attachListeners() {
        if (attached) return
        attached = true
        mp.setOnCompletionListener {
            if (hotMode) {
                hotIndex = (hotIndex + 1) % hotSongs.size
                playHot(hotIndex)
            } else {
                _playing.value = false
                MusicState.update(currentName, currentArtist, false)
            }
        }
        mp.setOnErrorListener { _, _, _ ->
            _playing.value = false
            _loading.value = false
            MusicState.isPlaying = false
            true
        }
    }

    fun isRunning() = _current.value != null

    fun play(song: MusicApi.Song, hot: Boolean = false) {
        hotMode = hot
        _current.value = song
        currentName = song.name; currentArtist = song.artist
        _loading.value = true
        Thread {
            val url = MusicApi.resolvePlayUrl(song.id)
            val canPlay = MusicApi.probeAudio(url)
            ctx?.let { MusicState.update(song.name, song.artist, false) }
            if (!canPlay) {
                _loading.value = false
                if (hot) { hotIndex = (hotIndex + 1) % hotSongs.size; playHot(hotIndex) }
                return@Thread
            }
            try {
                runCatching { mp.reset() }
                mp.setDataSource(url)
                mp.setOnPreparedListener {
                    it.start()
                    _playing.value = true
                    MusicState.update(song.name, song.artist, true)
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                _loading.value = false
            }
            _loading.value = false
        }.start()
        startService()
    }

    fun playHot(i: Int) {
        val nm = hotSongs[i % hotSongs.size]
        Thread {
            val found = MusicApi.search(nm).firstOrNull()
            if (found != null) { hotIndex = i; play(found, true) }
            else if (i + 1 < hotSongs.size) playHot(i + 1)
        }.start()
    }

    fun toggle() {
        if (_current.value == null) { playHot(0); return }
        if (_playing.value) pause() else resume()
    }

    fun pause() {
        runCatching { if (mp.isPlaying) mp.pause() }
        _playing.value = false
        MusicState.isPlaying = false
    }

    fun resume() {
        if (_current.value == null) return
        runCatching { mp.start() }
        _playing.value = true
        MusicState.isPlaying = true
    }

    fun stop() {
        runCatching { mp.stop() }
        _playing.value = false
        _current.value = null
        MusicState.isPlaying = false
        stopService()
    }

    private fun startService() {
        val c = ctx ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(android.content.Intent(c, MusicService::class.java))
        } else {
            c.startService(android.content.Intent(c, MusicService::class.java))
        }
    }
    private fun stopService() {
        ctx?.stopService(android.content.Intent(ctx, MusicService::class.java))
    }
}
