package com.mikasa.ui.launcher

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import com.mikasa.ui.theme.glassPanel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* ================= 音乐 API ================= */

object MusicApi {
    private const val SEARCH_API = "https://apis.netstart.cn/music/search"
    private const val MUSIC_API = "https://node.api.xfabe.com/api/wangyi/music"
    private const val LYRICS_API = "https://node.api.xfabe.com/api/wangyi/lyrics"

    data class Song(val id: String, val name: String, val artist: String, val album: String)

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).readText()
            } else {
                BufferedReader(InputStreamReader(conn.errorStream)).readText()
            }
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        } finally {
            conn.disconnect()
        }
    }

    /** 搜索歌曲 */
    fun search(keyword: String): List<Song> {
        val url = "$SEARCH_API?keywords=${URLEncoder.encode(keyword, "UTF-8")}&limit=30"
        return try {
            val json = JSONObject(httpGet(url))
            val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
            (0 until songs.length()).mapNotNull { i ->
                val s = songs.getJSONObject(i)
                val artist = s.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "未知歌手"
                Song(
                    id = s.optString("id"),
                    name = s.optString("name"),
                    artist = artist,
                    album = s.optJSONObject("album")?.optString("name") ?: ""
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析可播放地址（多接口兜底）：
     * 1. 网易官方外链（最稳，无版权/付费歌返回404）
     * 2. gdstudio 解析接口（付费歌也能拿到 CDN 直链）
     */
    fun resolvePlayUrl(id: String): String {
        val official = "https://music.163.com/song/media/outer/url?id=$id.mp3"
        if (probeAudio(official)) return official
        return try {
            val json = JSONObject(httpGet("https://music-api.gdstudio.xyz/api.php?types=url&id=$id"))
            json.optString("url").ifEmpty { official }
        } catch (e: Exception) {
            official
        }
    }

    /** 探测音频流是否可播放（无版权歌曲返回无效重定向） */
    fun probeAudio(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code == 302) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return false
                return probeAudio(loc)
            }
            val ct = conn.getContentType() ?: ""
            val ok = code in 200..299 && (
                ct.contains("audio") || ct.contains("mpeg") ||
                    ct.contains("octet-stream") || ct.contains("mp3") || ct.isEmpty()
                )
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** 获取歌词（兼容 data.lyric 与 lrc.lyric 两种格式） */
    fun getLyrics(id: String): String {
        val url = "$LYRICS_API?id=$id"
        return try {
            val json = JSONObject(httpGet(url))
            val lyric = json.optJSONObject("data")?.optString("lyric")
                ?: json.optJSONObject("lrc")?.optString("lyric")
            lyric?.ifEmpty { null } ?: "（无歌词）"
        } catch (e: Exception) {
            "（歌词获取失败）"
        }
    }
}

/* ================= 自绘音符图标（双八分音符） ================= */

@Composable
fun MusicNoteIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.07f

        fun note(cx: Float, cy: Float, scale: Float) {
            val r = w * 0.09f * scale
            // 符头（实心圆）
            drawCircle(color, radius = r, center = Offset(cx, cy))
            // 符杆
            drawLine(
                color,
                Offset(cx, cy - r * 0.3f),
                Offset(cx, cy - h * 0.52f * scale),
                strokeWidth = stroke, cap = StrokeCap.Round
            )
            // 符尾（向左下旗）
            drawLine(
                color,
                Offset(cx, cy - h * 0.52f * scale),
                Offset(cx - w * 0.16f * scale, cy - h * 0.36f * scale),
                strokeWidth = stroke, cap = StrokeCap.Round
            )
            drawLine(
                color,
                Offset(cx - w * 0.16f * scale, cy - h * 0.36f * scale),
                Offset(cx - w * 0.16f * scale, cy - h * 0.26f * scale),
                strokeWidth = stroke, cap = StrokeCap.Round
            )
        }

        note(w * 0.36f, h * 0.72f, 1.0f)
        note(w * 0.72f, h * 0.82f, 0.85f)
        // 连接横梁
        drawLine(
            color,
            Offset(w * 0.36f, h * 0.48f),
            Offset(w * 0.72f, h * 0.58f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
    }
}

/* ================= 音乐页面 ================= */

@Composable
fun MusicPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    var keyword by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf(listOf<MusicApi.Song>()) }
    var searching by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf("") }
    var showLyrics by remember { mutableStateOf(false) }

    // 常驻音乐引擎：不随页面销毁 → 切页/退软件继续播，只有手动暂停才停
    LaunchedEffect(Unit) {
        MusicEngine.init(context)
        if (!MusicEngine.isRunning()) MusicEngine.playHot(0)
    }
    val current = MusicEngine.current
    val playing = MusicEngine.playing
    val loadingSong = MusicEngine.loading
    val hotSongs = MusicEngine.hotSongs

    fun playSong(song: MusicApi.Song) { MusicEngine.play(song, false) }
    fun playHot(i: Int) { MusicEngine.playHot(i) }
    fun togglePlay() { MusicEngine.toggle() }

    // 拉取当前歌歌词
    LaunchedEffect(current?.id) {
        val c = current
        if (c != null) lyrics = withContext(Dispatchers.IO) { MusicApi.getLyrics(c.id) }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MusicNoteIcon(MaterialTheme.colorScheme.primary, Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("音乐", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                Text("在线搜索 · 网易云曲库", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 搜索栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp)),
                placeholder = { Text("搜歌名 / 歌手…", fontSize = 14.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.7f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.55f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        val kw = keyword.trim()
                        if (kw.isEmpty()) return@clickable
                        searching = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { MusicApi.search(kw) }
                            songs = result
                            searching = false
                            if (result.isEmpty()) {
                                Toast.makeText(context, "没有找到相关歌曲", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // 热门推荐（自动播放）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔥 热门·自动播放", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hotSongs.forEachIndexed { i, n ->
                val active = current?.name == n
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.65f))
                        .clickable { playHot(i) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(n, fontSize = 12.sp, color = if (active) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 当前播放卡片
        if (current != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(dark, alpha = 0.55f, shape = RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 迷你音符
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        MusicNoteIcon(Color.White, Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            current!!.name,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            current!!.artist,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 播放/暂停
                    if (loadingSong) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { togglePlay() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (playing) {
                                // 自绘暂停图标（两条竖线）
                                Canvas(modifier = Modifier.size(20.dp)) {
                                    val bw = size.width * 0.18f
                                    val h = size.height
                                    drawRoundRect(
                                        Color.White,
                                        topLeft = Offset(size.width * 0.24f, 0f),
                                        size = androidx.compose.ui.geometry.Size(bw, h),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                    )
                                    drawRoundRect(
                                        Color.White,
                                        topLeft = Offset(size.width * 0.58f, 0f),
                                        size = androidx.compose.ui.geometry.Size(bw, h),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    null, tint = Color.White, modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    // 歌词
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .borderCompat()
                            .clickable { showLyrics = !showLyrics },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("词", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // 歌词面板
        if (showLyrics) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .glassPanel(dark, alpha = 0.55f, shape = RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(lyrics, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
        }

        // 搜索结果
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("搜索中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (songs.isNotEmpty()) {
            Text(
                "搜索结果 · ${songs.size} 首",
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(songs) { song ->
                    SongRow(
                        song = song,
                        active = current?.id == song.id,
                        onClick = { playSong(song) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MusicNoteIcon(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text("搜索你喜欢的歌，点击播放", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 描边辅助 */
private fun Modifier.borderCompat(): Modifier =
    this.border(1.5.dp, Color(0xFFE5E5E5), RoundedCornerShape(24.dp))

/** 歌曲列表行 */
@Composable
private fun SongRow(song: MusicApi.Song, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f) else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号/音符
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text("♪", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.name,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${song.artist} · ${song.album}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Icon(
            Icons.Filled.PlayArrow,
            null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}