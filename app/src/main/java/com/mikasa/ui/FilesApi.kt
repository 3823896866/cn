package com.mikasa.ui

import org.json.JSONArray

/** 后端文件（上传）对接：纯 JDK HttpURLConnection，无第三方依赖。 */
object FilesApi {
    const val base = "http://101.35.2.133"

    data class FileItem(val name: String, val zone: String, val size: String, val storagePath: String)

    fun url(item: FileItem) = base + "/uploads/" + java.net.URLEncoder.encode(item.storagePath, "UTF-8")

    /** 取某分区文件列表（zone=功能/美化）；失败返回空。 */
    fun list(zone: String): List<FileItem> = try {
        val conn = java.net.URL(base + "/api/files?zone=" + java.net.URLEncoder.encode(zone, "UTF-8")).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        val code = conn.responseCode
        val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
        conn.disconnect()
        if (code in 200..299) {
            val arr = JSONArray(body)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                FileItem(
                    o.optString("name", "文件"),
                    o.optString("zone", zone),
                    o.optString("size", ""),
                    o.optString("storagePath", o.optString("name", "file"))
                )
            }
        } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** 下载到目标目录，返回本地路径；失败返回 null。 */
    fun download(item: FileItem, destDir: java.io.File, onProgress: (Double) -> Unit = {}): String? = try {
        val conn = java.net.URL(url(item)).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 60000
        val total = conn.contentLength.toLong()
        val outFile = java.io.File(destDir, item.name)
        java.io.File(destDir, item.name + ".part").delete()
        val out = java.io.FileOutputStream(java.io.File(destDir, item.name + ".part"))
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            var read = input.read(buf)
            var sum = 0L
            while (read != -1) {
                out.write(buf, 0, read)
                sum += read
                if (total > 0) onProgress(sum.toDouble() / total)
                read = input.read(buf)
            }
        }
        out.close()
        conn.disconnect()
        java.io.File(destDir, item.name + ".part").renameTo(outFile)
        onProgress(1.0)
        outFile.absolutePath
    } catch (e: Exception) {
        null
    }
}
