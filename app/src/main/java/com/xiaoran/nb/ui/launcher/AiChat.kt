package com.xiaoran.nb.ui.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoran.nb.ui.model.MikasaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小染助手 —— 本地 AI（无需联网、无 API Key）
 * - 基于关键词的本地响应引擎
 * - 上下文限制 200 字（超出自动裁剪最早消息）
 */
object XiaoMiAi {

    data class Msg(val role: String, val content: String)

    /** 本地响应（无需网络） */
    fun chat(messages: List<Msg>): String {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: ""

        return when {
            lastUser.contains("你好") || lastUser.contains("嗨") ||
            lastUser.contains("hello", true) || lastUser.contains("hi", true) ->
                "你好呀～我是小染，你的本地 AI 助手！有什么我能帮你的吗？喵～"

            lastUser.contains("你是谁") ->
                "我是小染，MikasaUI 的本地 AI 助手，无需联网就能使用！喵～"

            lastUser.contains("功能") || lastUser.contains("介绍") ->
                "MikasaUI 的功能包括：\n" +
                "· 悬浮球 + 控制面板（可拖动、缩放、折叠组）\n" +
                "· 灵动岛（帧率/温度/电量/音乐）\n" +
                "· 在线音乐搜索播放\n" +
                "· 音量键控制 UI 显隐\n" +
                "· 防录屏自动隐藏\n" +
                "· 文件下载\n" +
                "· 液态玻璃主题"

            lastUser.contains("音乐") ->
                "在「音乐」页可以搜索网易云歌曲，支持在线播放和歌词！喵～"

            lastUser.contains("灵动岛") ->
                "灵动岛是仿 iOS 的顶部胶囊，点击展开可看时间/帧率/温度/电量/歌曲，动画有3档流畅度可选！喵～"

            lastUser.contains("下载") || lastUser.contains("文件") ->
                "「文件」页支持下载资源文件，左上角有刷新按钮。喵～"

            lastUser.contains("shizuku", true) ->
                "Shizuku 可以在无 Root 的情况下提供更高权限，「权限」页有授权入口！喵～"

            lastUser.contains("谢谢") || lastUser.contains("感谢") ->
                "不客气～有问题随时找小染！喵～"

            lastUser.contains("帮助") || lastUser.contains("help", true) ->
                "试试问我：\n· 功能介绍\n· 音乐怎么搜\n· 灵动岛怎么用\n· 文件下载\n我都在这里等你！喵～"

            lastUser.isNotEmpty() ->
                "收到你的消息「$lastUser」！我是小染，本地 AI 助手，你试试问我「功能介绍」或「音乐怎么用」？喵～"

            else ->
                "我是小染，MikasaUI 的本地 AI 助手。发点什么跟我聊聊吧！喵～"
        }
    }

    /** 客服系统：优先走后端（可被人工接管/配置问答）；连不上后端则回退本地关键词回复 */
    fun reply(messages: List<Msg>, cardKey: String, device: String, sessionId: String, image: String = ""): String {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val remote = MikasaApi.csSend(cardKey, device, lastUser, image, sessionId)
        return if (remote != null) remote else chat(messages)
    }

    /** 裁剪上下文到 200 字以内 */
    fun trimContext(messages: List<Msg>): List<Msg> {
        val out = messages.toMutableList()
        var total = out.sumOf { it.content.length }
        while (total > 200 && out.size > 1) {
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
        if (raw.isNullOrBlank())
            return listOf(Msg("assistant", "我是小染，MikasaUI 的本地 AI 助手，无需联网！有什么想问的？喵～"))
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Msg(o.optString("r"), o.optString("c"))
            }
        } catch (e: Exception) {
            listOf(Msg("assistant", "我是小染，MikasaUI 的本地 AI 助手，无需联网！有什么想问的？喵～"))
        }
    }
}

/**
 * 小染助手聊天页（受控组件：状态由外部持有，滑动/退出不丢失）
 * @param messages 消息列表（外部持久化）
 * @param loading 是否等待回复
 * @param onSend 发送回调（外部处理本地逻辑）
 * @param onClear 手动清空聊天
 */
@Composable
fun AiChatPage(
    messages: List<XiaoMiAi.Msg>,
    loading: Boolean,
    onSend: (text: String, image: String) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var pendingImg by remember { mutableStateOf("") } // 待发送的图片 dataURL

    // 选择图片→base64
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val b64 = uri?.let { u ->
            try {
                val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() }
                if (bytes != null) "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) else ""
            } catch (e: Exception) { "" }
        }
        if (!b64.isNullOrEmpty()) pendingImg = b64
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
                Text("小染客服 · 后端可人工接管", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 手动清空按钮
            Text(
                "清空",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.3f))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
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

        // 待发图片预览
        if (pendingImg.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.3f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📎 已附图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                    "移除", fontSize = 12.sp, color = Color(0xFFE53935),
                    modifier = Modifier.clickable { pendingImg = "" }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // 输入栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 附图按钮
            Text(
                "📷", fontSize = 20.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.4f))
                    .clickable { pickImage.launch("image/*") }
                    .padding(10.dp)
            )
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.4f))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(22.dp)),
                placeholder = { Text("问小染点什么…", fontSize = 14.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.4f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.4f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.width(8.dp))
            // 发送按钮（液态玻璃风格）
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                    .clickable {
                        val text = input.trim()
                        if (text.isEmpty() || loading) return@clickable
                        val img = pendingImg
                        input = ""
                        pendingImg = ""
                        onSend(text, img)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 聊天气泡：AI 左侧半透明白 / 用户右侧主题色（液态玻璃风格） */
@Composable
private fun ChatBubble(msg: XiaoMiAi.Msg) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // 助手头像（小染 · 粉紫色圆）
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB388FF).copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Text("染", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else Color.White.copy(alpha = 0.55f)
                )
                .border(
                    1.dp,
                    if (isUser) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.5f),
                    RoundedCornerShape(18.dp)
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
