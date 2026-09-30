package lo.naui.term

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Termux 环境（bootstrap）的安装器。
 *
 * 为什么要「路径重写」：
 *   termux 的二进制是照着 `$PREFIX=/data/data/com.termux/files/usr` 编的，
 *   DT_RPATH 和 shebang 里全是这个**硬编码路径**。我们包名是 lo.naui，
 *   路径一变，动态链接器就找不到 libc，什么都跑不起来。
 *
 *   而这些字符串都在 `.dynstr` / 文本里，是 **null 结尾**的 ——
 *   换成更短的新路径、后面补 \0，就能保持所有偏移不变，
 *   于是不用重编、不用改包名也能跑。
 *
 * 另外 Android 10 起 app 数据目录禁止 exec（W^X），
 * termux 自己的做法是把 targetSdk 留在 28 绕过去，我们也照做。
 */
object Bootstrap {

    /** termux 编译时写死的那个前缀 */
    private const val OLD_PREFIX = "/data/data/com.termux/files/usr"
    private const val OLD_HOME = "/data/data/com.termux/files/home"

    /** 几个镜像轮着试，国内直连 github 经常不通 */
    private val MIRRORS = listOf(
        "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
        "https://ghfast.top/https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
        "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
        "https://ghproxy.net/https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
    )

    private const val MAX_REWRITE_BYTES = 48L * 1024 * 1024

    fun prefix(ctx: Context): File = File(ctx.filesDir, "usr")
    fun home(ctx: Context): File = File(ctx.filesDir, "home")

    /** 装好了没 */
    fun isInstalled(ctx: Context): Boolean = File(prefix(ctx), "bin/bash").exists()

    /** 已经装了多少字节（给界面看） */
    fun installedSize(ctx: Context): Long =
        runCatching { prefix(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    /**
     * 下载 + 解压 + 修符号链接 + 权限 + 路径重写。
     * [onProgress] 第一个参数 0..1，第二个是当前在干嘛。
     */
    suspend fun install(
        ctx: Context,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val prefix = prefix(ctx)
            val home = home(ctx)
            home.mkdirs()

            // ---- 1. 下载 ----
            val zip = File(ctx.cacheDir, "bootstrap.zip")
            if (!zip.exists() || zip.length() < 1024L * 1024L) {
                var ok = false
                var lastErr: Throwable? = null
                for (url in MIRRORS) {
                    val r = runCatching { download(url, zip) { p -> onProgress(p * 0.45f, "下载中") } }
                    if (r.isSuccess) { ok = true; break }
                    lastErr = r.exceptionOrNull()
                }
                if (!ok) throw IllegalStateException(
                    "bootstrap 下载失败：" + (lastErr?.message ?: "都试过了") +
                        "\n可以手动把 bootstrap-aarch64.zip 放到 " + zip.absolutePath
                )
            }

            // ---- 2. 解压 ----
            prefix.mkdirs()
            unzip(zip, prefix) { p -> onProgress(0.45f + p * 0.35f, "解压中") }

            // ---- 3. 符号链接 ----
            onProgress(0.82f, "修符号链接")
            fixSymlinks(prefix)

            // ---- 4. 权限 ----
            onProgress(0.86f, "设权限")
            fixPermissions(prefix)

            // ---- 5. 路径重写 ----
            onProgress(0.90f, "重写路径（termux → 本应用）")
            val newPrefix = prefix.absolutePath
            val newHome = home.absolutePath
            var done = 0
            val all = prefix.walkTopDown().filter { it.isFile }.toList()
            all.forEach { f ->
                rewriteFile(f, OLD_PREFIX, newPrefix, OLD_HOME, newHome)
                rewriteTextFile(f, OLD_PREFIX, newPrefix, OLD_HOME, newHome)
                done++
                if (done % 40 == 0) {
                    onProgress(0.90f + 0.09f * done / all.size.coerceAtLeast(1), "重写路径")
                }
            }

            runCatching { zip.delete() }
            onProgress(1f, "装好了")
        }
    }

    /* ---------------- 下载 ---------------- */

    private fun download(url: String, out: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nakour")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP " + code + " @ " + url.substringAfter("//").take(40))
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (30L * 1024 * 1024)
        out.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(128 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    done += n
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        if (out.length() < 1024L * 1024L) throw IllegalStateException("下下来的文件太小，八成不是 bootstrap")
    }

    /* ---------------- 解压 ---------------- */

    private fun unzip(zipFile: File, target: File, onProgress: (Float) -> Unit) {
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().toList()
            var total = 0L
            entries.forEach { total += it.size.coerceAtLeast(0) }
            if (total <= 0L) total = 1L

            var done = 0L
            entries.forEach { entry ->
                val name = entry.name
                if (name.contains("..")) return@forEach
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath)) return@forEach

                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    runCatching {
                        zf.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { output -> input.copyTo(output, 128 * 1024) }
                        }
                    }
                }
                done += entry.size.coerceAtLeast(0)
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /* ---------------- 符号链接 ---------------- */

    /** bootstrap 里带一个 SYMLINKS.txt，Java 的 zip 保不住链接，得自己建 */
    private fun fixSymlinks(prefix: File) {
        val f = File(prefix, "SYMLINKS.txt")
        if (!f.exists()) return
        runCatching {
            f.readLines().forEach { line ->
                val parts = line.split("←→")
                if (parts.size != 2) return@forEach
                val target = parts[0]
                val link = File(prefix, parts[1])
                link.parentFile?.mkdirs()
                runCatching {
                    if (link.exists()) link.delete()
                    Os.symlink(target, link.absolutePath)
                }
            }
            f.delete()
        }
    }

    /* ---------------- 权限 ---------------- */

    private fun fixPermissions(prefix: File) {
        val execDirs = listOf("bin", "libexec", "libexec/termux-am")
        execDirs.forEach { d ->
            File(prefix, d).listFiles()?.forEach { f ->
                runCatching { Os.chmod(f.absolutePath, 0b111101101) }   // 0755
            }
        }
        // 目录给 755
        prefix.walkTopDown().filter { it.isDirectory }.forEach {
            runCatching { Os.chmod(it.absolutePath, 0b111101101) }
        }
    }

    /* ---------------- 路径重写 ---------------- */

    /**
     * 二进制里原地替换：新路径更短，后面补 \0。
     * 这样文件长度不变，ELF 里所有偏移都不用动。
     */
    private fun rewriteFile(file: File, oldPrefix: String, newPrefix: String, oldHome: String, newHome: String) {
        if (!file.isFile) return
        val len = file.length()
        if (len <= 0L || len > MAX_REWRITE_BYTES) return

        runCatching {
            val bytes = file.readBytes()
            var changed = false
            var out = bytes

            fun patch(o: String, n: String) {
                val ob = o.toByteArray(Charsets.UTF_8)
                val nb = n.toByteArray(Charsets.UTF_8)
                if (nb.size > ob.size) return                       // 新路径更长就不动，免得踩坏偏移
                val padded = ByteArray(ob.size)
                System.arraycopy(nb, 0, padded, 0, nb.size)
                var idx = indexOf(out, ob)
                while (idx >= 0) {
                    System.arraycopy(padded, 0, out, idx, ob.size)
                    changed = true
                    idx = indexOf(out, ob, idx + ob.size)
                }
            }

            patch(oldPrefix, newPrefix)
            patch(oldHome, newHome)

            if (changed) file.writeBytes(out)
        }
    }

    /** 纯文本（shebang / 配置文件）重写：这些可以直接变短，不用补零 */
    private fun rewriteTextFile(file: File, oldPrefix: String, newPrefix: String, oldHome: String, newHome: String) {
        if (!file.isFile) return
        val len = file.length()
        if (len <= 0L || len > 8L * 1024 * 1024) return

        runCatching {
            val head = ByteArray(minOf(512, len.toInt()))
            file.inputStream().use { it.read(head) }
            // 带 NUL 的多半是二进制，交给上面那套处理
            if (head.any { it == 0.toByte() }) return@runCatching

            val text = file.readText()
            if (!text.contains(oldPrefix) && !text.contains(oldHome)) return@runCatching
            val next = text.replace(oldPrefix, newPrefix).replace(oldHome, newHome)
            file.writeText(next)
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        outer@ for (i in from..(data.size - pattern.size)) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /* ---------------- 环境 ---------------- */

    /** 装好 bootstrap 之后给终端用的环境变量 */
    fun environ(ctx: Context): Array<String> {
        val p = prefix(ctx).absolutePath
        val h = home(ctx).absolutePath
        val tmp = File(p, "tmp").apply { mkdirs() }.absolutePath
        return arrayOf(
            "PREFIX=$p",
            "HOME=$h",
            "TMPDIR=$tmp",
            "PATH=$p/bin:$p/bin/applets:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=$p/lib",
            "SHELL=$p/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "TERMINFO=$p/share/terminfo",
            "TERMUX_VERSION=0.118",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "EXTERNAL_STORAGE=/storage/emulated/0",
        )
    }

    /** 装好之后的默认 shell */
    fun shellPath(ctx: Context): String =
        if (isInstalled(ctx)) File(prefix(ctx), "bin/bash").absolutePath else "/system/bin/sh"

    /** 删掉整个环境 */
    fun uninstall(ctx: Context): Boolean = runCatching {
        prefix(ctx).deleteRecursively()
    }.getOrDefault(false)
}
