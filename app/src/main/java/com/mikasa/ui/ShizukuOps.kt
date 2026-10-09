package com.mikasa.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 提权操作（不依赖 Root）：
 * - shizukuShell：用 Shizuku 的 newProcess 以提权身份跑 shell 命令。
 * - shizukuInject：真实注入——把已下载文件（公开暂存，提权可读）解压/写入目标游戏 pak 目录。
 * - autoGrant：自动授权 Shizuku（检测→唤起 App 建通道→requestPermission→轮询确认）。
 * - terminalCommand：终端口令「小染注入权限」→ 自动授权 + 自动注入。
 * 所有方法都通过 onLog 逐步回显，避免"点了没反应、没提示"。
 */
object ShizukuOps {

    const val CMD_GRANT = "小染注入权限"

    /** 用 Shizuku 跑一条 shell 命令（提权身份）。返回 (成功, 退出码, 输出)。 */
    fun shizukuShell(cmd: String): Triple<Boolean, Int, String> {
        return try {
            if (!Shizuku.pingBinder()) return Triple(false, -1, "通道未连接")
            val svc = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                ?: return Triple(false, -1, "binder 获取失败（未连接 Shizuku）")
            val proc: IRemoteProcess = svc.newProcess(arrayOf("sh", "-c", cmd), arrayOf(), "/")
                ?: return Triple(false, -1, "newProcess 失败")
            val out = StringBuilder()
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(proc.getInputStream())
                    .bufferedReader().use { out.append(it.readText()) }
            }
            val err = StringBuilder()
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(proc.getErrorStream())
                    .bufferedReader().use { err.append(it.readText()) }
            }
            val code = proc.waitFor()
            runCatching { proc.destroy() }
            Triple(code == 0, code, ((out.toString() + " " + err.toString()).trim().take(600)))
        } catch (e: Throwable) {
            Triple(false, -1, "异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** shell 双引号转义（处理 \ $ " ` 换行） */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("$", "\\$").replace("\"", "\\\"")
            .replace("`", "\\`").replace("\n", " ")

    /** 公共「下载/小染注入」目录（shell 身份可读）。 */
    private fun publicDir(): File {
        val base = Environment.getExternalStorageDirectory().absolutePath
        return File(base, "Download/小染注入").also { runCatching { it.mkdirs() } }
    }

    /** 把 App 本地文件（xiaoranDir）暂存成 shell 可读的公共路径。API29+ 走 MediaStore，旧版直写。返回 shell 可见路径；失败回退到原路径。 */
    private fun stageToPublic(context: Context, from: File, name: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/小染注入")
                    put(MediaStore.Downloads.SIZE, from.length())
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) cr.openOutputStream(uri)?.use { os -> from.inputStream().use { it.copyTo(os) } }
                File(publicDir(), name).absolutePath
            } else {
                File(publicDir(), name).let { pub ->
                    runCatching { from.copyTo(pub, overwrite = true) }
                    pub.absolutePath
                }
            }
        } catch (e: Exception) {
            from.absolutePath
        }
    }

    /** 真实注入：用 Shizuku 提权把已下载文件（公开暂存）解压/写入 targetDir。返回 (成功, 说明)。 */
    fun shizukuInject(context: Context, fileName: String, targetDir: String, onLog: (String) -> Unit = {}): Pair<Boolean, String> {
        onLog("> 检查 Shizuku 通道/授权")
        if (!Shizuku.pingBinder())
            return false to "Shizuku 通道未连接：到「权限→Shizuku 终端」输入「小染注入权限」启动通道，或在 Shizuku App 点「启动」（无线调试/ADB）"
        if (Shizuku.checkSelfPermission() != 0)
            return false to "Shizuku 未授权：在「权限→Shizuku 终端」输入「小染注入权限」自动授权，或在 Shizuku App 内确认授权框"
        val xia = File(FilesApi.xiaoranDir(context), fileName)
        onLog("> 定位源文件：$fileName")
        if (!xia.exists() || xia.length() <= 0)
            return false to "本地没有「$fileName」，请先到「文件」页下载"
        val shellSrc = stageToPublic(context, xia, fileName)
        onLog("> 源文件（提权可见）：$shellSrc\n> 目标目录：$targetDir")
        onLog("> 提权写入中…")
        val S = esc(shellSrc); val T = esc(targetDir.trimEnd('/'))
        val script = """
            mkdir -p "$T" 2>/dev/null || { echo DIR_FAIL; exit 2; }
            [ -f "$S" ] || { echo SRC_NOFILE; exit 3; }
            case "$S" in
              *.zip|*.ZIP)
                if unzip -o "$S" -d "$T" >/dev/null 2>&1; then echo UNZIP_OK;
                elif busybox unzip -o "$S" -d "$T" >/dev/null 2>&1; then echo UNZIP_OK;
                elif toybox unzip -o "$S" -d "$T" >/dev/null 2>&1; then echo UNZIP_OK;
                else echo NO_UNZIP; fi ;;
              *)
                if cp -f "$S" "$T"/ >/dev/null 2>&1; then echo CP_OK; else echo CP_FAIL; fi ;;
            esac
            echo DONE
        """.trimIndent()
        val (_, code, text) = shizukuShell(script)
        onLog("> shell 输出：${text.ifBlank { "(无)" }} (code=$code)")
        return when {
            text.contains("UNZIP_OK") -> true to "已解压「$fileName」→ $targetDir（Shizuku 提权）"
            text.contains("CP_OK") -> true to "已写入「$fileName」→ $targetDir（Shizuku 提权）"
            text.contains("DIR_FAIL") -> false to "无法创建目标目录 $targetDir（权限不足或路径错误）"
            text.contains("SRC_NOFILE") -> false to "提权进程读不到源文件（ADB 模式读不了 App 私有目录）；请重试，或改用 Root 模式的 Shizuku"
            text.contains("NO_UNZIP") -> false to "手机 shell 缺少 unzip，无法解压 zip；请改用单文件(.pak)或手动解压"
            text.contains("CP_FAIL") -> false to "写入目标失败：$targetDir（权限不足或路径不可写）"
            else -> false to "命令已执行但未成功（code=$code）；输出：${text.ifBlank { "无" }}"
        }
    }

    /** 自动授权 Shizuku：检测安装 →（未连接则唤起 App 建通道并等待）→ requestPermission → 轮询确认。返回是否已授权。 */
    fun autoGrant(context: Context, onLog: (String) -> Unit): Boolean {
        onLog("> 检测 Shizuku 安装")
        if (!shizukuInstalled(context)) {
            onLog("× 未安装 Shizuku（包名 moe.shizuku.privileged.api），请先安装 Shizuku App 再重试"); return false
        }
        onLog("√ 已安装 Shizuku")
        onLog("> 检测/建立通道")
        if (!shizukuConnected()) {
            onLog("× 通道未连接 → 正在唤起 Shizuku App，请在其中点「启动/开始」（无线调试或 ADB 配对）")
            startShizuku(context)
            var c = false
            for (i in 1..20) { Thread.sleep(500); if (shizukuConnected()) { c = true; break } }
            if (!c) {
                onLog("× 仍无通道：请完成 Shizuku 启动（无线调试配对/ADB/Root），启动成功后重新输入「$CMD_GRANT」"); return false
            }
            onLog("√ 通道已连接")
        }
        onLog("> 请求授权（留意 Shizuku 的授权弹框并点「允许」）")
        runCatching { Shizuku.requestPermission(2001) }
        var granted = false
        repeat(15) { if (Shizuku.checkSelfPermission() == 0) { granted = true; return@repeat }; Thread.sleep(600) }
        if (granted) onLog("✅ Shizuku 已授权，可真实注入") else onLog("× 授权未完成：请在 Shizuku 授权弹框点「允许」，再重新输入「$CMD_GRANT」")
        return granted
    }

    /** 终端命令解析：「小染注入权限」→ 自动授权 + 自动注入已下载文件。返回 true 表示已处理。 */
    fun terminalCommand(context: Context, raw: String, onLog: (String) -> Unit): Boolean {
        val cmd = raw.trim()
        onLog("\$ $cmd")
        if (cmd.isBlank()) { onLog("（空输入）试试：$CMD_GRANT"); return true }
        if (cmd.contains(CMD_GRANT)) {
            val granted = autoGrant(context, onLog)
            if (granted) autoInjectFirst(context, onLog)
            return true
        }
        onLog("未知命令。可用口令：「$CMD_GRANT」（自动授权并注入已下载文件）")
        return true
    }

    /** 自动注入：取后端默认导入路径 + 第一个已下载文件。 */
    private fun autoInjectFirst(context: Context, onLog: (String) -> Unit) {
        onLog("> 授权成功，开始自动注入")
        val st = FilesApi.settings()
        val path = if (!st.importPathDefault.isNullOrBlank()) st.importPathDefault else st.importPathPak
        if (path.isNullOrBlank()) { onLog("× 后端未配置导入路径，无法注入"); return }
        val files = FilesApi.list("功能") + FilesApi.list("美化")
        val dl = files.filter { FilesApi.isDownloaded(context, it) }
        if (dl.isEmpty()) { onLog("× 没有已下载文件：先到「文件」页下载，再重试"); return }
        val f = dl.first()
        onLog("> 注入「${f.name}」→ $path")
        val (ok, msg) = shizukuInject(context, f.name, path, onLog)
        onLog(if (ok) "✅ $msg" else "× $msg")
    }

    /** Shizuku 是否已安装（各版本/变体包名）。 */
    fun shizukuInstalled(context: Context): Boolean =
        listOf(
            "moe.shizuku.privileged.api", "moe.shizuku.privilege.api",
            "rikka.shizuku", "dev.rikka.shizuku", "com.rikka.shizuku", "moe.shizuku.shizuku"
        ).any { p -> runCatching { context.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }

    /** 通道是否已连接。 */
    fun shizukuConnected(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** 是否已授权。 */
    fun shizukuGranted(): Boolean = try { Shizuku.checkSelfPermission() == 0 } catch (e: Throwable) { false }

    /** 唤起 Shizuku App（逐个尝试包名）。 */
    private fun startShizuku(context: Context): Boolean {
        for (pkg in listOf("moe.shizuku.privileged.api", "moe.shizuku.privilege.api", "rikka.shizuku", "dev.rikka.shizuku", "com.rikka.shizuku")) {
            val l = context.packageManager.getLaunchIntentForPackage(pkg)
            if (l != null) {
                l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(l) }.isSuccess) return true
            }
        }
        return false
    }
}
