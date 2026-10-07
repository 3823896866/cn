package com.mikasa.ui

import org.json.JSONArray

/** 后端文件（上传）对接：纯 JDK HttpURLConnection，无第三方依赖。 */
object FilesApi {
    const val base = "http://101.35.2.133"

    data class FileItem(val name: String, val zone: String, val size: String, val storagePath: String)

    /** 后端设置（导入路径等）。失败返回空默认。 */
    data class Settings(
        val softwareEnabled: Boolean,
        val importPathDefault: String,
        val importPathPak: String,
        val unbindLimit: Int
    )

    /** 取后端设置（默认/pak 导入路径等）。 */
    fun settings(): Settings {
        val empty = Settings(false, "", "", 3)
        return try {
            val conn = java.net.URL(base + "/api/settings").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()
            if (code !in 200..299) return empty
            val o = org.json.JSONObject(body)
            Settings(
                o.optBoolean("softwareEnabled", false),
                o.optString("importPathDefault", ""),
                o.optString("importPathPak", ""),
                o.optInt("unbindLimit", 3)
            )
        } catch (e: Exception) {
            empty
        }
    }

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
    /** 真实下载：先下到缓存(校验字节数>0)，再写 App 外部「小染注入」+ 公开 Download/小染注入(MediaStore)。失败返 null。 */
    fun downloadToPublic(context: android.content.Context, item: FileItem, onProgress: (Double) -> Unit = {}): String? {
        val tmp = java.io.File(context.cacheDir, "dl_real")
        if (!tmp.exists()) tmp.mkdirs()
        val localPath = download(item, tmp, onProgress) ?: return null
        val local = java.io.File(localPath)
        if (local.length() <= 0) { local.delete(); return null }
        try { local.copyTo(java.io.File(xiaoranDir(context), local.name), overwrite = true) } catch (e: Exception) {}
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val cr = context.contentResolver
                val values = android.content.ContentValues()
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, item.name)
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/小染注入")
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) cr.openOutputStream(uri)?.use { os -> local.inputStream().use { ins -> ins.copyTo(os) } }
                return "手机「下载/小染注入」→ ${item.name}"
            } catch (e: Exception) {
            }
        }
        return "App 目录「小染注入」→ ${item.name}"
    }

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

    /** 注入：下载选中文件到缓存，再按「解压 zip / 单文件」写入 targetDir（同名覆盖）。返回写入文件数；下载失败返回 -1。 */
    fun inject(context: android.content.Context, item: FileItem, targetDir: java.io.File): Int {
        val cache = java.io.File(context.cacheDir, "inject_tmp").apply { mkdirs() }
        val local = download(item, cache) ?: return -1
        val f = java.io.File(local)
        return try {
            if (f.name.endsWith(".zip", ignoreCase = true)) {
                importZip(f, targetDir)
            } else {
                if (!targetDir.exists()) targetDir.mkdirs()
                f.copyTo(java.io.File(targetDir, f.name), overwrite = true)
                1
            }
        } catch (e: Exception) {
            -1
        } finally {
            f.delete()
            java.io.File(cache, item.name + ".part").delete()
        }
    }

}
