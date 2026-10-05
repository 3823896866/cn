package com.xiaoran.nb.ui.launcher

import androidx.compose.foundation.background
import com.xiaoran.nb.ui.glass.liquidGlass
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * 小米 AI 助手「雷电法军」
 * - 接入小米 MiMo 大模型 API（OpenAI 兼容格式，模型 mimo-v2.5）
 * - 上下文限制 188 字（超出自动裁剪最早消息）
 */
object XiaoMiAi {
    private const val API_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    private const val API_KEY = "sk-c4oqh9sz2p3cghn4f1c4ubqhng64ghwuqy0vm6rdb0hepr1q"
    private const val MODEL = "mimo-v2.5"

    // 助手人设
    private val SYSTEM_PROMPT =
        "你是雷电法军，小染 的 AI 助手，说话简洁有趣，用中文回复，喜欢用'喵'结尾。"

    data class Msg(val role: String, val content: String)

    /** 调小米 MiMo API（阻塞，需在协程中调用） */
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
        messages.forEach { arr.put(JSONObject().put("r", it.role).put("c", it.content)) }
        return arr.toString()
    }

    /** 从 prefs 反序列化 */
    fun load(raw: String?): List<Msg> {
        if (raw.isNullOrBlank()) return listOf(Msg("assistant", "我是小染客服，有什么不懂的问题来问我吧，小染祝你天天开心\n小提示：输入转人工会有真实的客服回复哦～"))
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Msg(o.optString("r"), o.optString("c"))
            }
        } catch (e: Exception) {
            listOf(Msg("assistant", "我是小染客服，有什么不懂的问题来问我吧，小染祝你天天开心\n小提示：输入转人工会有真实的客服回复哦～"))
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
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

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
                Text("小染 · 后端在线 · 上下文 188 字", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

        // 输入栏
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
                placeholder = { Text("问小染点什么…", fontSize = 14.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.6f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.5f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.width(8.dp))
            // 发送按钮
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .liquidGlass(23.dp, MaterialTheme.colorScheme.primary)
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
                    else Color.White
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                msg.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}