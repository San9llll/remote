package lo.naui.term

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege

/**
 * 环境备份 / 恢复。
 *
 * 把 `files/usr` 和 `files/home` 一起打成 tar.gz —— termux 那边也是这么干的。
 * 有 root / Shizuku 就借它的 shell，没有就用 App 自己的 sh（数据目录本来就在自己名下，够用）。
 */
object Backup {

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 默认放这儿：能被文件管理器看到 */
    fun defaultDir(): File = File("/storage/emulated/0/Download")

    fun suggestName(): String = "nakour-backup-" + stamp.format(Date()) + ".tar.gz"

    suspend fun backup(ctx: Context, out: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            out.parentFile?.mkdirs()
            val parent = ctx.filesDir.absolutePath
            val cmd = "tar -czf " + q(out.absolutePath) + " -C " + q(parent) + " usr home"
            val outText = runShell(ctx, cmd)
            if (!out.exists() || out.length() <= 0L) {
                throw IllegalStateException("打包失败：" + outText.takeLast(300))
            }
            out
        }
    }

    suspend fun restore(ctx: Context, input: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = ctx.filesDir.absolutePath
            val cmd = "tar -xzf " + q(input.absolutePath) + " -C " + q(parent)
            val outText = runShell(ctx, cmd)
            if (!Bootstrap.isInstalled(ctx)) {
                throw IllegalStateException("解压了但没找到 bin/bash：" + outText.takeLast(300))
            }
            // 恢复出来的文件权限会丢，重新刷一遍
            onProgressFixPerms(ctx)
        }
    }

    /** 恢复之后把可执行位补回来 */
    private suspend fun onProgressFixPerms(ctx: Context) {
        val prefix = Bootstrap.prefix(ctx).absolutePath
        runShell(ctx, "chmod -R 700 " + q(prefix + "/bin"))
        runShell(ctx, "chmod -R 755 " + q(prefix + "/lib"))
    }

    fun listBackups(dir: File): List<File> =
        runCatching {
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".tar.gz") } ?: emptyArray())
                .sortedByDescending { it.lastModified() }
        }.getOrDefault(emptyList())

    /* ---------------- 跑命令 ---------------- */

    private suspend fun runShell(ctx: Context, cmd: String): String {
        if (Privilege.level(ctx) != PrivLevel.Normal) {
            Privilege.exec(ctx, cmd)?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(180, TimeUnit.SECONDS)
                p.destroy()
                text
            }.getOrDefault("")
        }
    }

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"
}
