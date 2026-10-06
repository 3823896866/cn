package com.mikasa.ui.launcher

/**
 * 全局音乐播放状态（音乐页写入，悬浮灵动岛读取显示）
 */
object MusicState {
    @Volatile var songName: String = ""
    @Volatile var artist: String = ""
    @Volatile var isPlaying: Boolean = false

    fun update(song: String?, artist: String?, playing: Boolean) {
        songName = song ?: ""
        this.artist = artist ?: ""
        isPlaying = playing
    }

    fun clear() {
        songName = ""
        artist = ""
        isPlaying = false
    }
}