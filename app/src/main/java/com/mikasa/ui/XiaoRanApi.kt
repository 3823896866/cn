package com.mikasa.ui

import org.json.JSONArray
import org.json.JSONObject

/** 小染后端对接（纯 JDK HttpURLConnection，无第三方依赖）。连不上时返回安全默认。 */
object XiaoRanApi {
    const val base = "http://101.35.2.133"

    data class CardVerify(val ok: Boolean, val reason: String, val type: String, val expiresAt: String)

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

    /** 客服消息（后端可人工接管/配置问答）；离线返回 null（调用方回退本地）。 */
    fun csSend(cardKey: String, device: String, text: String, image: String, sessionId: String): String? {
        val r = req("POST", "/api/cs/message", JSONObject()
            .put("sessionId", sessionId).put("cardKey", cardKey)
            .put("device", device).put("text", text).put("image", image).toString())
            ?: return null
        return runCatching { JSONObject(r).optString("reply", "") }.getOrNull()?.ifBlank { null }
    }
}
