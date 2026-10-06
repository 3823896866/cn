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

    /** 应用内「小染注入」文件夹（无需额外权限，App 私有外部目录）。 */
    fun xiaoranDir(context: android.content.Context): java.io.File {
        val d = context.getExternalFilesDir("小染注入") ?: java.io.File(context.filesDir, "小染注入")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 流式下载（用于公开目录下载）。返回是否成功。 */
    private fun downloadStream(item: FileItem, out: java.io.OutputStream, onProgress: (Double) -> Unit): Boolean = try {
        val conn = java.net.URL(url(item)).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 60000
        val total = conn.contentLength.toLong()
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024); var read = input.read(buf); var sum = 0L
            while (read != -1) {
                out.write(buf, 0, read); sum += read
                if (total > 0) onProgress(sum.toDouble() / total)
                read = input.read(buf)
            }
        }
        conn.disconnect(); onProgress(1.0); true
    } catch (e: Exception) { false }

    /** 下载到手机公开「下载/小染注入/」（Android 10+ 用 MediaStore，文件管理器可直接看到）；旧版退回 App 私有目录。返回展示路径。 */
    fun downloadToPublic(context: android.content.Context, item: FileItem, onProgress: (Double) -> Unit = {}): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cr = context.contentResolver
            val values = android.content.ContentValues()
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, item.name)
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/小染注入")
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                cr.openOutputStream(uri).use { os -> if (os != null) { downloadStream(item, os, onProgress); return "手机「下载」目录 → 小染注入 → ${item.name}" } }
            }
        }
        // 退回：App 私有 小染注入 目录
        val dir = xiaoranDir(context)
        val f = java.io.File(dir, item.name)
        f.outputStream().use { os -> if (!downloadStream(item, os, onProgress)) return null }
        f.absolutePath
    } catch (e: Exception) { null }

    /** 导入 zip：自动解压到 targetDir，同名文件直接覆盖。返回解压出的文件数。 */
    fun importZip(zipFile: java.io.File, targetDir: java.io.File): Int {
        if (!targetDir.exists()) targetDir.mkdirs()
        var count = 0
        java.util.zip.ZipFile(zipFile).use { zf ->
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                val outFile = java.io.File(targetDir, e.name)
                // 防 zip-slip：目标必须在 targetDir 内
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) continue
                if (e.isDirectory) { outFile.mkdirs(); continue }
                outFile.parentFile?.mkdirs()
                zf.getInputStream(e).use { ins -> outFile.outputStream().use { ins.copyTo(it) } }
                count++
            }
        }
        return count
    }

}
