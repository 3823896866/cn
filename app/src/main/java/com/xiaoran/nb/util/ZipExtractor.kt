package com.xiaoran.nb.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * 通用 zip → 文件夹 快速解压：多线程、同名覆盖、防路径穿越/zip炸弹。
 * 目标目录由调用方指定（小染里取自后端 /api/config 的 importDir，不写进游戏目录）。
 */
object ZipExtractor {
    data class Report(val files: Int, val overwritten: Int, val skipped: Int, val totalBytes: Long)

    /**
     * @param zipFile   要解压的 zip
     * @param targetDir 目标目录（同名文件/目录直接覆盖）
     * @param threads   并发线程数（默认 4）
     * @param onProgress 进度回调 0..1（按已写字节占比）
     */
    fun extract(zipFile: File, targetDir: File, threads: Int = 4,
                onProgress: (Double) -> Unit = {}): Report {
        targetDir.mkdirs()
        val targetCanon = targetDir.canonicalFile.absolutePath

        // 收集数据条目 + 目录，拦截越界
        val data = ArrayList<Pair<String, File>>()
        val totalBytes = AtomicLong(0)
        var skipped = 0
        val zf = ZipFile(zipFile)
        try {
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                val out = File(targetDir, e.name).canonicalFile
                val ok = out.absolutePath.startsWith(targetCanon)
                if (!ok) { skipped++; continue }
                if (e.isDirectory) out.mkdirs()
                else {
                    totalBytes.addAndGet(e.size)
                    data.add(Pair(e.name, out))
                }
            }
        } finally { zf.close() }

        val doneBytes = AtomicLong(0)
        val doneFiles = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        val tasks = data.map { (name, out) ->
            pool.submit {
                val z2 = ZipFile(zipFile)
                try {
                    z2.getInputStream(z2.getEntry(name)).use { ins ->
                        BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
                            val buf = ByteArray(1 shl 19)
                            var n = ins.read(buf)
                            while (n > 0) {
                                os.write(buf, 0, n)
                                doneBytes.addAndGet(n.toLong())
                                n = ins.read(buf)
                            }
                        }
                    }
                } catch (ignored: Exception) {
                } finally { z2.close() }
                val p = doneBytes.get().let { if (totalBytes.get() > 0) it * 1.0 / totalBytes.get() else 1.0 }
                onProgress(minOf(1.0, p))
                doneFiles.incrementAndGet()
            }
        }
        tasks.forEach { it.get() }   // 等全部完成
        pool.shutdown()
        onProgress(1.0)
        return Report(files = doneFiles.get(), overwritten = 0, skipped = skipped, totalBytes = totalBytes.get())
    }
}
