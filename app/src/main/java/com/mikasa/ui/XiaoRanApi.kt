package com.mikasa.ui

import org.json.JSONArray
import org.json.JSONObject

/** 小染后端对接（纯 JDK HttpURLConnection，无第三方依赖）。连不上时返回安全默认。 */
object XiaoRanApi {
    const val base = "http://101.35.2.133"

    data class CardVerify(val ok: Boolean, val reason: String, val type: String, val expiresAt: String)

    /** 客服发送结果：reply（机器人/未匹配回复）+ status(bot/human) + sessionId。 */
    data class CsResult(val reply: String?, val status: String, val sessionId: String)

    private fun req(method: String, path: String, body: String? = null): String? {
        return try {
            val conn = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 10000
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                      else (conn.errorStream?.bufferedReader()?.readText() ?: "")
            conn.disconnect()
            if (code in 200..299) text else null
        } catch (e: Exception) {
            null
        }
    }

    /** 卡密验证（后端：类型/到期/设备绑定/解绑次数）。 */
    fun verifyCard(code: String, device: String): CardVerify {
        val r = req("POST", "/api/cards/verify", JSONObject().put("code", code).put("device", device).toString())
            ?: return CardVerify(false, "网络异常，请检查后端", "", "")
        return runCatching {
            val o = JSONObject(r)
            CardVerify(o.optBoolean("ok"), o.optString("reason", ""), o.optString("type", "月卡"), o.optString("expiresAt", ""))
        }.getOrDefault(CardVerify(false, r.take(40), "", ""))
    }

    /** 公告列表 → "【标题】内容"。 */
    fun announcements(): List<String> {
        val r = req("GET", "/api/announcements") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(r)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.optString("title"); val c = o.optString("content")
                if (t.isBlank()) c else "【$t】$c"
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 发客服消息：返回 机器人回复 + 会话状态(bot/human)；离线返回 null（调用方回退本地）。 */    fun csSend(cardKey: String, device: String, text: String, image: String, sessionId: String): CsResult? {
        val r = req("POST", "/api/cs/message", JSONObject()
            .put("sessionId", sessionId).put("cardKey", cardKey)
            .put("device", device).put("text", text).put("image", image).toString())
            ?: return null
        return runCatching {
            val o = JSONObject(r)
            CsResult(
                o.optString("reply", "").ifBlank { null },
                o.optString("status", "bot"),
                o.optString("sessionId", sessionId)
            )
        }.getOrNull()
    }

    /** 转人工：标记会话为人工并记录用户身份（卡密/设备），供后台直接看到是哪个用户。 */
    fun csHuman(cardKey: String, device: String, sessionId: String) {
        req("POST", "/api/cs/human", JSONObject()
            .put("sessionId", sessionId).put("cardKey", cardKey)
            .put("device", device).toString())
    }

    /** 取某会话里客服(agent)已回复的消息（用于前端轮询接收人工回复）。 */
    fun agentReplies(sessionId: String): List<String> {
        val r = req("GET", "/api/cs/sessions?sid=" + java.net.URLEncoder.encode(sessionId, "UTF-8")) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(r)
            if (arr.length() == 0) emptyList() else {
                val msgs = arr.getJSONObject(0).optJSONArray("messages") ?: JSONArray()
                (0 until msgs.length()).mapNotNull { i ->
                    val m = msgs.getJSONObject(i)
                    if (m.optString("role") == "agent") m.optString("text").ifBlank { null }
                    else null
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 使用人数（后端：当前有效卡密数，卡密到期自动减少）。失败返回 0。 */
    fun activeUsers(): Int {
        val r = req("GET", "/api/stats/active") ?: return 0
        return runCatching { JSONObject(r).optInt("active", 0) }.getOrDefault(0)
    }

    /** 后端版本号。失败返回默认。 */
    fun serverVersion(): String {
        val r = req("GET", "/api/settings") ?: return "1.0"
        return runCatching { JSONObject(r).optString("version", "").ifBlank { "1.0" } }.getOrDefault("1.0")
    }

    data class AgentMsg(val text: String, val image: String)

    /** 取某会话里客服(agent)消息（含图片 URL）。失败返回空。 */
    fun agentMessages(sessionId: String): List<AgentMsg> {
        val r = req("GET", "/api/cs/sessions?sid=" + java.net.URLEncoder.encode(sessionId, "UTF-8")) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(r)
            if (arr.length() == 0) emptyList() else {
                val msgs = arr.getJSONObject(0).optJSONArray("messages") ?: JSONArray()
                (0 until msgs.length()).mapNotNull { i ->
                    val m = msgs.getJSONObject(i)
                    if (m.optString("role") == "agent") {
                        val img = m.optString("image", "")
                        AgentMsg(m.optString("text", ""), if (img.isBlank()) "" else base + "/uploads/" + img)
                    } else null
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 会话状态：bot/human/agent/ended。失败返回 bot。 */
    fun csStatus(sessionId: String): String {
        val r = req("GET", "/api/cs/sessions?sid=" + java.net.URLEncoder.encode(sessionId, "UTF-8")) ?: return "bot"
        return runCatching {
            val arr = JSONArray(r)
            if (arr.length() == 0) "bot" else arr.getJSONObject(0).optString("status", "bot")
        }.getOrDefault("bot")
    }

    data class AiConfig(val enabled: Boolean, val greeting: String)

    /** AI 助手开关 + 后端可设的自动问候语。失败返默认。 */
    fun aiConfig(): AiConfig {
        val def = AiConfig(true, "我是小染，有什么可以帮你？")
        val r = req("GET", "/api/settings") ?: return def
        return runCatching {
            val o = JSONObject(r)
            AiConfig(o.optBoolean("aiEnabled", true), o.optString("aiGreeting", def.greeting))
        }.getOrDefault(def)
    }

    /** 后端预设按钮（固定回答）：(按钮文字, 回答)。 */
    fun aiButtons(): List<Pair<String, String>> {
        val r = req("GET", "/api/ai/buttons") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(r)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("label", "") to o.optString("answer", "")
            }.filter { it.first.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 下个版本更新内容（后端 settings.nextVersion）。 */
    fun nextVersion(): String {
        val r = req("GET", "/api/settings") ?: return ""
        return runCatching { JSONObject(r).optString("nextVersion", "") }.getOrDefault("")
    }

    /** 支持 +1（后端计支持率）。 */
    fun support() { req("POST", "/api/support", "{}"); }

    /** 提交反馈建议。 */
    fun submitFeedback(text: String) { req("POST", "/api/feedback", JSONObject().put("text", text).toString()) }
}
