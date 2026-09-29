package lo.naui.sys

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件操作。
 *
 * 有 root / Shizuku 就走 shell（能碰 /data 那种地方），
 * 只有普通权限就退回 java.io.File（只在自己能看的范围里动）。
 * 长按菜单里的复制 / 移动 / 压缩，目标目录默认是**另一栏**当前的位置。
 */
object FsOps {

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun parentOf(path: String): String {
        val t = path.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }

    fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else dir + "/" + name

    /** 目标目录里已经有同名的话，加个 (2) 之类的后缀 */
    fun uniqueTarget(dir: String, name: String): String {
        var target = join(dir, name)
        if (!File(target).exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (File(target).exists() && i < 999) {
            target = join(dir, base + "(" + i + ")" + ext)
            i++
        }
        return target
    }

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    /** 有权限就用 shell 干；返回 true 表示命令跑通了 */
    private suspend fun shell(ctx: Context, cmd: String): Boolean {
        if (Privilege.level(ctx) == PrivLevel.Normal) return false
        val out = Privilege.exec(ctx, "(" + cmd + ") >/dev/null 2>&1 && echo __OK__")
        return out?.contains("__OK__") == true
    }

    suspend fun copy(ctx: Context, src: String, destDir: String): Boolean = withContext(Dispatchers.IO) {
        val target = uniqueTarget(destDir, name(src))
        if (shell(ctx, "cp -rf " + q(src) + " " + q(target))) return@withContext true
        localCopy(File(src), File(target))
    }

    suspend fun move(ctx: Context, src: String, destDir: String): Boolean = withContext(Dispatchers.IO) {
        val target = uniqueTarget(destDir, name(src))
        if (shell(ctx, "mv -f " + q(src) + " " + q(target))) return@withContext true
        runCatching { File(src).renameTo(File(target)) }.getOrDefault(false)
    }

    suspend fun delete(ctx: Context, path: String): Boolean = withContext(Dispatchers.IO) {
        if (shell(ctx, "rm -rf " + q(path))) return@withContext true
        runCatching { File(path).deleteRecursively() }.getOrDefault(false)
    }

    suspend fun rename(ctx: Context, path: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val target = join(parentOf(path), newName)
        if (shell(ctx, "mv -f " + q(path) + " " + q(target))) return@withContext true
        runCatching { File(path).renameTo(File(target)) }.getOrDefault(false)
    }

    /**
     * 压缩成一个 .tar.gz 放到 [destDir]。
     * 用 tar 不用 zip —— Android 自带的是 toybox，里头没有 zip 只有 tar。
     */
    suspend fun archive(ctx: Context, paths: List<String>, destDir: String): Boolean =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext false
            val stampName = if (paths.size == 1) {
                name(paths[0]).substringBeforeLast('.') + ".tar.gz"
            } else {
                "archive_" + System.currentTimeMillis() + ".tar.gz"
            }
            val dest = uniqueTarget(destDir, stampName)

            val cmd = if (paths.size == 1) {
                val p = paths[0]
                "tar -czf " + q(dest) + " -C " + q(parentOf(p)) + " " + q(name(p))
            } else {
                "tar -czf " + q(dest) + " " + paths.joinToString(" ") { q(it) }
            }
            if (shell(ctx, cmd)) return@withContext true

            // 没权限：拿 java 自己打个 zip（后缀跟着换）
            val zipDest = dest.removeSuffix(".tar.gz") + ".zip"
            localZip(paths, zipDest)
        }

    /** 属性窗口要的那几行 */
    suspend fun stat(ctx: Context, path: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<String, String>>()
        out += "路径" to path
        out += "名称" to name(path)

        val f = File(path)
        if (f.canRead()) {
            out += "类型" to if (f.isDirectory) "目录" else (path.substringAfterLast('.', "").ifBlank { "文件" })
            out += "修改时间" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(f.lastModified()))
        }

        // 目录大小要 du，文件直接 length
        val sizeText = runCatching {
            if (Privilege.level(ctx) != PrivLevel.Normal) {
                Privilege.exec(ctx, "du -sh " + q(path) + " 2>/dev/null")?.trim()?.substringBefore('\t')
            } else null
        }.getOrNull()
        if (!sizeText.isNullOrBlank()) {
            out += "大小" to sizeText
        } else {
            out += "大小" to (if (f.isDirectory) "—" else f.length().toString() + " B")
        }

        val perm = runCatching {
            Privilege.exec(ctx, "ls -ld " + q(path) + " 2>/dev/null")?.trim()
        }.getOrNull()
        if (!perm.isNullOrBlank()) out += "权限" to perm

        out
    }

    /* ---------------- 没权限时的本地实现 ---------------- */

    private fun localCopy(src: File, dest: File): Boolean = runCatching {
        if (src.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            src.listFiles()?.forEach { c -> localCopy(c, File(dest, c.name)) }
            true
        } else {
            dest.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            true
        }
    }.getOrDefault(false)

    private fun localZip(paths: List<String>, dest: String): Boolean = runCatching {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            paths.forEach { p ->
                val root = File(p)
                val base = parentOf(p)
                fun walk(f: File) {
                    val entryName = f.absolutePath.removePrefix(base).trimStart('/')
                    if (f.isDirectory) {
                        zos.putNextEntry(ZipEntry(entryName + "/"))
                        zos.closeEntry()
                        f.listFiles()?.forEach { walk(it) }
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                walk(root)
            }
        }
        true
    }.getOrDefault(false)
}
