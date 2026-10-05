package com.xiaoran.nb.net

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.Base64

/**
 * 小染自动注入 —— 前端对接后端（server/server.js）的客户端。
 * 纯 HttpURLConnection，不新增依赖。BASE 指向后端地址（按部署环境改）。
 */
object XrApi {
    // 后端公网地址（已部署到 101.35.2.133:8787，systemd 常驻）
    const val BASE: String = "http://101.35.2.133:8787"

    data class UpdateInfo(val enabled: Boolean, val minVersion: String, val url: String, val force: Boolean,
                          val content: String, val image: String)
    data class StatusInfo(val serviceDisabled: Boolean,
                          val announceTitle: String, val announceContent: String, val announceIcon: String,
                          val update: UpdateInfo, val online: Int, val total: Int)
    data class FileItem(val id: String, val name: String, val size: Long, val url: String)
    data class CsItem(val q: String, val a: String)
    data class StatsInfo(val online: Int, val total: Int)

    private fun post(path: String, json: String, adminKey: String? = null): JSONObject {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (adminKey != null) setRequestProperty("x-admin", adminKey)
            connectTimeout = 8000; readTimeout = 20000
        }
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        val o = try { JSONObject(body) } catch (e: Exception) { JSONObject().put("ok", false).put("msg", "HTTP $code") }
        if (!o.optBoolean("ok", false) && !o.has("cards")) o.put("ok", false)
        return o
    }

    private fun get(path: String): JSONObject {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8000; readTimeout = 20000
        }
        val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: "{}"
        conn.disconnect()
        return try { JSONObject(body) } catch (e: Exception) { JSONObject() }
    }

    // ---- 卡密 ----
    /** 每次开悬浮窗都要重新校验（不自动登录）；校验通过=可用 */
    fun verifyCard(key: String, deviceId: String): Boolean =
        post("/api/card/verify", JSONObject().put("key", key).put("deviceId", deviceId).toString())
            .optBoolean("ok", false)

    /** 解绑（每个卡密仅一次） */
    fun unbind(key: String): Boolean =
        post("/api/card/unbind", JSONObject().put("key", key).toString()).optBoolean("ok", false)

    // ---- 状态 / 公告 / 更新 ----
    fun status(): StatusInfo {
        val o = get("/api/status")
        val a = o.optJSONObject("announcement") ?: JSONObject()
        val u = o.optJSONObject("update") ?: JSONObject()
        return StatusInfo(
            o.optBoolean("serviceDisabled", false),
            a.optString("title", ""), a.optString("content", ""), a.optString("icon", ""),
            UpdateInfo(u.optBoolean("enabled", false), u.optString("minVersion", "0.0.0"),
                u.optString("url", ""), u.optBoolean("force", false),
                u.optString("content", ""), u.optString("image", "")),
            o.optInt("online", 0), o.optInt("total", 0)
        )
    }
    fun stats(): StatsInfo { val o = get("/api/stats"); return StatsInfo(o.optInt("online", 0), o.optInt("total", 0)); }
    fun csQa(): List<CsItem> {
        val arr = get("/api/csQa").optJSONArray("qa") ?: return emptyList()
        val list = ArrayList<CsItem>()
        for (i in 0 until arr.length()) { val j = arr.optJSONObject(i) ?: continue; list.add(CsItem(j.optString("q", ""), j.optString("a", ""))) }
        return list
    }
    fun csMessage(card: String, device: String, text: String, mediaBase64: String = ""): Boolean =
        post("/api/cs/message", JSONObject().put("card", card).put("device", device)
            .put("text", text).put("media", mediaBase64).toString()).optBoolean("ok", false)

    // ---- 文件 / 音乐 ----
    fun files(): List<FileItem> = parseFiles(get("/api/files"))
    fun music(): List<FileItem> = parseFiles(get("/api/music"))
    /** zip 解压目标目录：新后端 importDir 为对象 {default,pak}，取 .default（旧字符串兜底） */
    fun importDir(): String {
        val c = get("/api/config"); val id = c.optJSONObject("importDir")
        if (id != null) return id.optString("default", "")
        return c.optString("importDir", "")
    }
    /** 视频背景：后端返回 /videoBg（相对路径）→ 拼 BASE 成完整 URL；http 原样；空=用本地默认 */
    fun videoBgUrl(): String {
        val v = get("/api/config").optString("videoBg", "")
        return when { v.startsWith("/") -> BASE + v; v.startsWith("http") -> v; else -> "" }
    }

    /** 上传文件到后端（base64） */
    fun uploadFile(name: String, content: ByteArray, music: Boolean): Boolean {
        val b64 = Base64.getEncoder().encodeToString(content)
        val o = post(if (music) "/api/music/upload" else "/api/file/upload",
            JSONObject().put("name", name).put("contentBase64", b64).toString())
        return o.optBoolean("ok", false)
    }

    /** 下载到本地目录，带进度回调（0..1）。用 Range 分块读以显示进度。 */
    fun download(url: String, destDir: File, name: String, onProgress: (Double) -> Unit = {}): Boolean {
        return try {
            val f = File(destDir, name); destDir.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("Range", "bytes=0-")
                connectTimeout = 10000; readTimeout = 30000
            }
            val total = conn.contentLengthLong.coerceAtLeast(-1L)
            conn.inputStream.use { ins ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(64 * 1024); var read = 0L; var n: Int
                    while (ins.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n); read += n
                        if (total > 0) onProgress((read.toDouble() / total).coerceAtMost(1.0))
                    }
                }
            }
            onProgress(1.0); true
        } catch (e: Exception) { false }
    }

    private fun parseFiles(o: JSONObject): List<FileItem> {
        val arr = o.optJSONArray("files") ?: return emptyList()
        val list = ArrayList<FileItem>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            list.add(FileItem(j.optString("id"), j.optString("name"),
                j.optLong("size"), j.optString("url").let { if (it.startsWith("/")) BASE + it else it }))
        }
        return list
    }
}
