package lo.naui.sys

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** 这台机器上能拿到的最高权限 */
enum class PrivLevel(val label: String, val rank: Int) {
    Root("root", 2),
    Shizuku("Shizuku", 1),
    Normal("普通用户", 0),
}

/** 一行文件/目录 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

/** 主页要展示的那条「当前最高权限」 */
data class TopPrivilege(
    val level: PrivLevel,
    val title: String,
    val subtitle: String,
)

/**
 * 权限分层。
 *
 * 同一个动作按「当前能拿到的最高权限」去执行：
 *   root   → `su -c`
 *   Shizuku→ `Shizuku.newProcess`（adb 免 root）
 *   普通   → 直接 java.io.File
 *
 * 高一层拿不到就自动往下一层退，调用方不用管。
 */
object Privilege {

    private const val EXEC_TIMEOUT_MS = 4000L

    @Volatile private var cachedLevel: PrivLevel? = null

    fun resetCache() {
        cachedLevel = null
    }

    /** Shizuku 服务在跑、而且给过本应用权限 */
    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun level(ctx: Context, force: Boolean = false): PrivLevel = withContext(Dispatchers.IO) {
        if (!force) cachedLevel?.let { return@withContext it }
        val l = when {
            Metrics.rootAvailable() -> PrivLevel.Root
            shizukuReady() -> PrivLevel.Shizuku
            else -> PrivLevel.Normal
        }
        cachedLevel = l
        l
    }

    /** 主页那条：只说当前最高的那个，普通用户返回 null（不展示） */
    suspend fun topInfo(ctx: Context): TopPrivilege? {
        return when (level(ctx)) {
            PrivLevel.Normal -> null
            PrivLevel.Shizuku -> {
                val s = ShizukuState.info(ctx)
                TopPrivilege(
                    level = PrivLevel.Shizuku,
                    title = "Shizuku",
                    subtitle = if (s.appVersion.isBlank()) "adb" else "adb · v" + s.appVersion,
                )
            }
            PrivLevel.Root -> {
                val r = Metrics.rootInfo()
                val name = r.name
                val bin = when {
                    name.startsWith("APatch") -> "apd"
                    name.startsWith("KernelSU") -> "ksud"
                    name.startsWith("Magisk") -> "magisk"
                    else -> "su"
                }
                TopPrivilege(
                    level = PrivLevel.Root,
                    title = name,
                    subtitle = if (r.version.isBlank()) bin else bin + " " + r.version,
                )
            }
        }
    }

    /* ---------------- 执行 ---------------- */

    suspend fun exec(ctx: Context, cmd: String): String? = withContext(Dispatchers.IO) {
        when (level(ctx)) {
            PrivLevel.Root -> rootExec(cmd)
            PrivLevel.Shizuku -> shizukuExec(cmd) ?: rootExec(cmd)
            PrivLevel.Normal -> null
        }
    }

    private fun rootExec(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val done = p.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroy()
            null
        } else {
            p.inputStream.bufferedReader().readText()
        }
    }.getOrNull()

    /** Shizuku：走的也是 shell，只是这个 shell 是 adb 起的，不用 root */
    private fun shizukuExec(cmd: String): String? = runCatching {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        process.destroy()
        out
    }.getOrNull()

    /* ---------------- 文件 ---------------- */

    suspend fun listDir(ctx: Context, path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val lv = level(ctx)
        if (lv == PrivLevel.Normal) return@withContext plainList(path)

        val quoted = path.replace("'", "'\\''")
        val out = exec(ctx, "ls -lA '$quoted' 2>/dev/null")
        if (out.isNullOrBlank()) return@withContext plainList(path)
        val parsed = parseLs(out, path)
        if (parsed.isEmpty()) plainList(path) else parsed
    }

    private fun plainList(path: String): List<FsEntry> {
        val f = File(path)
        val kids = runCatching { f.listFiles() }.getOrNull() ?: return emptyList()
        return kids.map {
            FsEntry(
                name = it.name,
                path = it.absolutePath,
                isDir = it.isDirectory,
                size = if (it.isDirectory) 0L else runCatching { it.length() }.getOrDefault(0L),
            )
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /**
     * 解析 `ls -lA`。
     * 形如：drwxr-xr-x 2 root root 4096 2024-01-01 12:00 name
     * 名字里有空格也能吃下来（limit = 9，剩下的整段都是名字）。
     */
    private fun parseLs(out: String, base: String): List<FsEntry> {
        val list = mutableListOf<FsEntry>()
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("total")) return@forEach
            val parts = t.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) return@forEach
            val perms = parts[0]
            if (perms.length < 10) return@forEach
            val name = parts[8]
            if (name == "." || name == "..") return@forEach
            val isDir = perms[0] == 'd'
            list += FsEntry(
                name = name,
                path = if (base.endsWith("/")) base + name else base + "/" + name,
                isDir = isDir,
                size = if (isDir) 0L else (parts[4].toLongOrNull() ?: 0L),
            )
        }
        return list.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 读文本（给 Agent 传附件用），普通权限读不了就退回来 */
    suspend fun readText(ctx: Context, path: String, maxBytes: Int = 256 * 1024): String? =
        withContext(Dispatchers.IO) {
            val f = File(path)
            if (f.canRead()) {
                return@withContext runCatching {
                    f.readText().take(maxBytes)
                }.getOrNull()
            }
            val snippet = exec(ctx, "head -c $maxBytes '$path' 2>/dev/null")
            snippet?.takeIf { it.isNotBlank() }
        }
}
