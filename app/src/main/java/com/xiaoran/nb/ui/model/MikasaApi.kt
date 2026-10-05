package com.xiaoran.nb.ui.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 前后端对接层（HTTP）。
 * 后端部署后把 baseUrl 改成手机可访问的地址（手机与后端需在同一局域网）；
 * 留空则全部回退到本地（MikasaData），App 仍可离线运行。
 */
object MikasaApi {

    // ★ 后端地址（已部署到服务器）；手机与后端需能互通。留空 = 纯本地离线。
    const val baseUrl = "http://101.35.2.133"

    fun connected() = baseUrl.isNotBlank()

    /** 通用请求（GET/POST/PUT/DELETE），失败或留空 baseUrl 时返回 null */
    private fun req(method: String, path: String, body: String? = null): String? {
        if (!connected()) return null
        return try {
            val conn = java.net.URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()
            if (code in 200..299) text else null
        } catch (e: Exception) {
            null
        }
    }

    /** 卡密验证：后端有则走后端；连不上则回退为"非空即通过"（离线可用） */
    fun verifyCard(code: String): Boolean {
        val r = req("POST", "/api/cards/verify", JSONObject().put("code", code).toString())
        if (r == null) return code.isNotBlank()
        return runCatching { JSONObject(r).optBoolean("ok", false) }.getOrDefault(false)
    }

    /** 公告列表（后端）→ "【标题】内容"；连不上回退本地 MikasaData.announcements */
    fun announcements(): List<String> {
        val r = req("GET", "/api/announcements") ?: return MikasaData.announcements
        return runCatching {
            val arr = JSONArray(r)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.optString("title"); val c = o.optString("content")
                if (t.isBlank()) c else "【$t】$c"
            }.filter { it.isNotBlank() }
        }.getOrDefault(MikasaData.announcements)
    }

    /** 更新信息（后端）→ 版本；连不上回退本地 VERSION */
    fun updateVersion(): String {
        val r = req("GET", "/api/update") ?: return MikasaData.VERSION
        return runCatching {
            JSONObject(r).optString("version").ifBlank { MikasaData.VERSION }
        }.getOrDefault(MikasaData.VERSION)
    }

    /** 某分区文件（后端）→ ResourceFile；连不上回退本地 MikasaData */
    fun files(zone: String): List<ResourceFile> {
        val fallback = if (zone == "美化") MikasaData.beautyFiles else MikasaData.functionFiles
        val r = req("GET", "/api/files?zone=" + java.net.URLEncoder.encode(zone, "UTF-8")) ?: return fallback
        return runCatching {
            val arr = JSONArray(r)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ResourceFile(o.optString("name"), o.optString("size"), zone)
            }
        }.getOrDefault(fallback)
    }

    /** 软件开关（后端设置）：false 表示后台已关闭软件，前端不可用。连不上回退为 true（离线可用） */
    fun softwareEnabled(): Boolean {
        val r = req("GET", "/api/settings") ?: return true
        return runCatching { JSONObject(r).optBoolean("softwareEnabled", true) }.getOrDefault(true)
    }

    /** 客服消息：优先后端（可配置问答/人工接管）；连不上返回 null（调用方回退本地） */
    fun csSend(cardKey: String, device: String, text: String, image: String, sessionId: String): String? {
        val r = req("POST", "/api/cs/message", JSONObject()
            .put("sessionId", sessionId).put("cardKey", cardKey)
            .put("device", device).put("text", text).put("image", image).toString())
        if (r == null) return null
        return runCatching { JSONObject(r).optString("reply") }.getOrNull()
    }
}
