package com.mikasa.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小染 AI 助手（小染助手）
 * - 接入小染大模型 API（OpenAI 兼容格式）
 * - 上下文限制 188 字（超出自动裁剪最早消息）
 */
object XiaoMiAi {
    private const val API_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    private const val API_KEY = "sk-c4oqh9sz2p3cghn4f1c4ubqhng64ghwuqy0vm6rdb0hepr1q"
    private const val MODEL = "mimo-v2.5"

    // 助手人设
    private val SYSTEM_PROMPT =
        "你是小染，小染注入 的 AI 助手，说话简洁有趣，用中文回复。"

    data class Msg(val role: String, val content: String, val image: String = "")

    /** 调小染 AI API（阻塞，需在协程中调用） */
    fun chat(messages: List<Msg>): String {
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            put("temperature", 0.7)
            put("max_tokens", 512)
        }

        val conn = URL(API_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(text)
                json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            } else {
                val err = BufferedReader(InputStreamReader(conn.errorStream)).readText()
                "请求失败(${conn.responseCode})：${err.take(80)}"
            }
        } catch (e: Exception) {
            "网络异常：${e.message ?: "未知错误"}"
        } finally {
            conn.disconnect()
        }
    }

    /** 裁剪上下文到 188 字以内 */
    fun trimContext(messages: List<Msg>): List<Msg> {
        val out = messages.toMutableList()
        var total = out.sumOf { it.content.length }
        while (total > 188 && out.size > 1) {
            val removed = out.removeAt(0)
            total -= removed.content.length
        }
        return out
    }

    /** 序列化到 prefs */
    fun save(messages: List<Msg>): String {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("r", it.role).put("c", it.content).put("i", it.image)) }
        return arr.toString()
    }

    /** 从 prefs 反序列化 */
    fun load(raw: String?): List<Msg> {
        if (raw.isNullOrBlank()) return listOf(Msg("assistant", "我是小染助手，小染 AI 已就位，有什么不懂的问题来问我吧～"))
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Msg(o.optString("r"), o.optString("c"), o.optString("i", ""))
            }
        } catch (e: Exception) {
            listOf(Msg("assistant", "我是小染助手，小染 AI 已就位，有什么不懂的问题来问我吧～"))
        }
    }
}

/**
 * AI 助手聊天页（受控组件：状态由外部持有，滑动/退出不丢失）
 * @param messages 消息列表（外部持久化）
 * @param loading 是否等待回复
 * @param onSend 发送回调（外部处理网络请求）
 * @param onClear 手动清空聊天
 */
@Composable
fun AiChatPage(
    messages: List<XiaoMiAi.Msg>,
    loading: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    aiEnabled: Boolean,
    aiButtons: List<Pair<String, String>>,
    onPreset: (String) -> Unit,
    humanMode: Boolean,
    onTransferHuman: () -> Unit,
    cardVerified: Boolean,
    onBuy: () -> Unit,
    onVerifyCard: (String) -> Unit
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var cardInput by remember { mutableStateOf("") }

    // 后端未开启 AI 助手 → 维护中
    if (!aiEnabled) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("⚙️", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text("功能维护中，等待通知哦", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        return
    }

    // 新消息自动滚到底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("小染助手", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text("小染助手 · 小染 AI · 上下文 188 字", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 转人工按钮（需已验证卡密才可点；未验证时置灰）
            Text(
                "转人工",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (cardVerified) Color.White else Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (cardVerified) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f))
                    .clickable(enabled = cardVerified) { onTransferHuman() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Spacer(Modifier.width(8.dp))
            // 手动清空按钮
            Text(
                "清空",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    .clickable { onClear() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages) { msg ->
                ChatBubble(msg)
                Spacer(Modifier.height(10.dp))
            }
            if (loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("小染思考中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 未验证卡密 → 卡密门槛（输入验证 / 购买）；验证后才能用预设按钮
        if (!cardVerified) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🔒 验证卡密", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "验证并绑定卡密后才能使用 AI 助手功能；不验证卡密无法使用。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onBuy, shape = RoundedCornerShape(12.dp)) {
                        Text("没有卡密？点我购买", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = cardInput,
                            onValueChange = { cardInput = it },
                            label = { Text("输入卡密") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                val c = cardInput.trim()
                                if (c.isNotEmpty()) { onVerifyCard(c); cardInput = "" }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("验证", fontSize = 13.sp) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        } else if (aiButtons.isNotEmpty()) {
            // 预设按钮（已验证卡密后可用；固定回答）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                aiButtons.forEach { (label, _) ->
                    Surface(
                        onClick = { onPreset(label) },
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // 输入栏：仅「已验证卡密 且 已转人工」后可发送消息
        if (humanMode && cardVerified) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp)),
                    placeholder = { Text("转人工：输入消息…", fontSize = 14.sp) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
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
                            val text = input.trim()
                            if (text.isEmpty() || loading) return@clickable
                            input = ""
                            onSend(text)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 聊天气泡：AI 左侧白色 / 用户右侧主题色 */
@Composable
private fun ChatBubble(msg: XiaoMiAi.Msg) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // 助手头像（雷电）
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFB300)),
                contentAlignment = Alignment.Center
            ) {
                Text("⚡", fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.7f)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (msg.image.isNotBlank()) RemoteImage(msg.image)
                if (msg.content.isNotBlank()) {
                    if (msg.image.isNotBlank()) Spacer(Modifier.height(6.dp))
                    Text(
                        msg.content,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 加载远程图片（agent 发的图）；加载中显示占位 */
@Composable
private fun RemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val bytes = conn.inputStream.use { it.readBytes() }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null,
            modifier = Modifier.widthIn(max = 240.dp).height(140.dp))
    } else {
        Box(
            Modifier.size(80.dp, 60.dp).background(Color(0x11000000)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}