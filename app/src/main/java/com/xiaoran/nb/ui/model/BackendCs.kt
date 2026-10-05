package com.xiaoran.nb.ui.model

import org.json.JSONObject

/** 自包含后端客服（纯 JDK HttpURLConnection，无第三方依赖）。连不上返回 null。 */
object BackendCs {
    const val baseUrl = "http://101.35.2.133"
    fun enabled() = baseUrl.isNotBlank()

    /** 发一条客服消息，返回后端回复；失败/离线返回 null（调用方回退本地）。 */
    fun send(cardKey: String, device: String, text: String, image: String, sessionId: String): String? {
        if (!enabled()) return null
        return try {
            val conn = java.net.URL(baseUrl + "/api/cs/message").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 12000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(JSONObject()
                    .put("sessionId", sessionId)
                    .put("cardKey", cardKey)
                    .put("device", device)
                    .put("text", text)
                    .put("image", image)
                    .toString().toByteArray())
            }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                      else (conn.errorStream?.bufferedReader()?.readText() ?: "")
            conn.disconnect()
            if (code in 200..299) JSONObject(body).optString("reply", "").ifBlank { null } else null
        } catch (e: Exception) {
            null
        }
    }
}
