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
    // 100+ 首热门歌（尽量免费/非 VIP；付费或无版权的会在 probeAudio 阶段自动跳下一首）
    val hotSongs = listOf(
        "起风了", "光年之外", "小幸运", "匆匆那年", "平凡之路", "成都", "像我这样的人", "漂洋过海来看你", "月亮代表我的心", "晴天",
        "七里香", "稻香", "告白气球", "安静", "东风破", "江南", "简单爱", "吻别", "同桌的你", "老男孩",
        "最初的梦想", "倔强", "夜空中最亮的星", "海阔天空", "光辉岁月", "蓝莲花", "红日", "真的爱你", "喜欢你", "后来",
        "演员", "泡沫", "童话", "十年", "爱情转移", "富士山下", "可惜不是你", "遇见", "听海", "单身情歌",
        "不得不爱", "小酒窝", "小跳蛙", "小星星", "小苹果", "小情歌", "月亮之上", "最炫民族风", "荷塘月色", "天竺少女",
        "新贵妃醉酒", "青花瓷", "菊花台", "发如雪", "夜曲", "千里之外", "听妈妈的话", "一路生花", "光年的距离", "说好的幸福呢",
        "可惜没如果", "爱如潮水", "大海", "朋友", "祝你一路顺风", "甜蜜蜜", "小城故事", "外婆的澎湖湾", "难忘今宵", "冬天里的一把火",
        "明天会更好", "我的中国心", "东方之珠", "一生有你", "白桦林", "涛声依旧", "对面的女孩看过来", "心太软", "你笑起来真好看", "孤勇者",
        "半糖主义", "彩虹", "我是一只小小鸟", "隐形的翅膀", "天空", "小城春晓", "月亮船", "爱很简单", "那些你很熟悉的梦", "想把我唱给你听",
        "小镇姑娘", "江海不渡你", "冬眠", "坏女孩", "心似烟火", "须欢尽", "雨爱", "可不可以", "罗生门", "小半",
        "静夜的森林", "星晴", "枫", "霍元甲", "无双", "止战之殇", "一路向北", "爱在西元前", "不能说的秘密", "曹操",
        "时间都去哪儿了", "常回家看看", "因为爱情", "修炼爱情"
    ).distinct()

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
                // 播放完一首后随机切到另一首热门（免费/可播的会在 playHot 里自动跳过）
                hotIndex = randomOtherIndex()
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
                if (hot) { hotIndex = randomOtherIndex(); playHot(hotIndex) }
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

    /** 灵动岛/其它入口：下一首（热门模式则随机） */
    fun next() {
        val n = hotSongs.size
        if (n == 0) return
        hotIndex = if (hotMode) randomOtherIndex() else (hotIndex + 1) % n
        playHot(hotIndex)
    }

    /** 灵动岛/其它入口：上一首 */
    fun prev() {
        val n = hotSongs.size
        if (n == 0) return
        hotIndex = if (hotMode) randomOtherIndex() else (hotIndex - 1 + n) % n
        playHot(hotIndex)
    }

    /** 随机取一个非当前的热门下标 */
    private fun randomOtherIndex(): Int {
        val n = hotSongs.size
        if (n <= 1) return 0
        val r = (0 until n).shuffled().first()
        return if (r == hotIndex) (r + 1) % n else r
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
        MusicState.clear()
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
