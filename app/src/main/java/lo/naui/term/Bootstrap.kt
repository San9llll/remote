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
 * --- 为什么要「路径重写」---
 * termux 的二进制是照着 `$PREFIX=/data/data/com.termux/files/usr` 编的，
 * DT_RPATH 和 shebang 里全是这个硬编码路径。我们包名是 lo.naui，
 * 路径一变动态链接器就找不到 libc，什么都跑不起来。
 * 这些字符串都在 `.dynstr` 里、是 null 结尾的 —— 换成更短的新路径、
 * 后面补 \0，就能保持所有偏移不变，于是不用重编也不用改包名。
 *
 * --- 为什么要有「从本地 zip 安装」---
 * 国内直连 github 经常不通，几个加速站也时好时坏。
 * 所以留一条后路：自己下好 zip，用文件选择器喂进来，一样能装。
 *
 * --- 安装失败最常见的原因 ---
 *  1. 下载被墙（现在有镜像轮询 + 本地 zip 两条路）
 *  2. 路径重写时把几十 MB 的文件整个读进内存 → OOM 被杀
 *     （现在只碰 8MB 以内的 ELF 和文本，且先看文件头，不是 ELF 就跳过）
 *  3. 解压出来的符号链接没重建（读 SYMLINKS.txt 补）
 */
object Bootstrap {

    private const val OLD_PREFIX = "/data/data/com.termux/files/usr"
    private const val OLD_HOME = "/data/data/com.termux/files/home"

    /**
     * 单个文件重写的上限。
     * 老版本是「整读进内存 + 再复制一份」，bootstrap 里有 30MB+ 的数据文件，
     * 手机上直接 OOM —— 这就是装不上/装了没反应的主因之一。
     */
    private const val MAX_REWRITE_BYTES = 8L * 1024 * 1024

    /** 直连 + 一圈加速站，轮着来 */
    private fun mirrors(arch: String): List<String> {
        val gh = "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-$arch.zip"
        return listOf(
            gh,
            "https://ghfast.top/$gh",
            "https://ghproxy.net/$gh",
            "https://gh-proxy.com/$gh",
            "https://ghproxy.cc/$gh",
            "https://github.moeyy.xyz/$gh",
            "https://hub.gitmirror.com/$gh",
            "https://gh.llkk.cc/$gh",
        )
    }

    fun prefix(ctx: Context): File = File(ctx.filesDir, "usr")
    fun home(ctx: Context): File = File(ctx.filesDir, "home")

    /** 装好了没 —— 宽松一点：bin 里有 bash 或者 sh 就算 */
    fun isInstalled(ctx: Context): Boolean {
        val bin = File(prefix(ctx), "bin")
        if (!bin.isDirectory) return false
        return File(bin, "bash").exists() || File(bin, "sh").exists()
    }

    fun installedSize(ctx: Context): Long =
        runCatching { prefix(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    /** 设备该下哪个架构 */
    fun arch(): String = when {
        android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("armeabi") } -> "arm"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "x86_64"
        else -> "i686"
    }

    /** cache 里那份 zip 放哪 */
    fun cachedZip(ctx: Context): File = File(ctx.cacheDir, "bootstrap.zip")

    /* ================= 入口 ================= */

    suspend fun install(
        ctx: Context,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = cachedZip(ctx)
            if (!zip.exists() || zip.length() < 1024L * 1024L) {
                downloadAny(ctx, zip, onProgress)
            } else {
                onProgress(0.45f, "用上次下好的包")
            }
            installFrom(ctx, zip, onProgress)
        }
    }

    /** 用户自己喂进来的 zip */
    suspend fun installFromUserZip(
        ctx: Context,
        src: File,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = cachedZip(ctx)
            runCatching { zip.delete() }
            src.copyTo(zip, overwrite = true)
            installFrom(ctx, zip, onProgress)
        }
    }

    private suspend fun installFrom(
        ctx: Context,
        zip: File,
        onProgress: (Float, String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val prefix = prefix(ctx)
        val home = home(ctx)
        home.mkdirs()

        // ---- 空间检查：解压后大概要 250MB ----
        val need = 300L * 1024 * 1024
        val free = runCatching { ctx.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free in 1 until need) {
            throw IllegalStateException(
                "空间不够：还要约 " + (need / 1024 / 1024) + "MB，现在只剩 " + (free / 1024 / 1024) + "MB"
            )
        }

        onProgress(0.46f, "检查压缩包")
        val entries = runCatching { ZipFile(zip).use { it.entries().toList() } }
            .getOrElse { throw IllegalStateException("这个 zip 打不开，可能没下完：" + (it.message ?: "")) }

        // ---- 清掉旧的一半安装 ----
        if (prefix.exists() && !isInstalled(ctx)) {
            onProgress(0.48f, "清掉上次没装完的残留")
            prefix.deleteRecursively()
        }
        prefix.mkdirs()

        onProgress(0.50f, "解压 " + entries.size + " 个文件")
        unzip(zip, prefix, entries) { p -> onProgress(0.50f + p * 0.30f, "解压中") }

        onProgress(0.82f, "重建符号链接")
        fixSymlinks(prefix)

        onProgress(0.86f, "设可执行权限")
        fixPermissions(prefix)

        onProgress(0.90f, "重写内嵌路径（termux → 本应用）")
        rewriteAll(ctx, prefix, onProgress)

        if (!isInstalled(ctx)) {
            throw IllegalStateException(
                "解压完了但 bin/ 里没有 bash 或 sh —— 这个 zip 可能不是 termux 的 bootstrap"
            )
        }
        onProgress(1f, "装好了")
    }

    /* ================= 下载 ================= */

    private suspend fun downloadAny(ctx: Context, out: File, onProgress: (Float, String) -> Unit) {
        val arch = arch()
        val list = mirrors(arch)
        var last: String? = null

        list.forEachIndexed { i, url ->
            val host = url.substringAfter("//").substringBefore('/').take(28)
            onProgress(0.02f, "试第 " + (i + 1) + "/" + list.size + " 个源：" + host)
            val r = runCatching {
                download(url, out) { p ->
                    onProgress(0.02f + p * 0.42f, "下载中 " + host + " " + (p * 100).toInt() + "%")
                }
            }
            if (r.isSuccess) return
            last = host + "：" + (r.exceptionOrNull()?.message ?: "")
            runCatching { out.delete() }
        }
        throw IllegalStateException(
            "所有镜像都没下下来（最后试的是 " + last + "）\n" +
                "可以自己下 bootstrap-" + arch + ".zip，然后用「从文件安装」喂进来"
        )
    }

    private fun download(url: String, out: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nakour")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP " + code)
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (40L * 1024 * 1024)
        out.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(256 * 1024)
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
        if (out.length() < 1024L * 1024L) {
            throw IllegalStateException("下下来才 " + (out.length() / 1024) + "KB，不是 bootstrap")
        }
    }

    /* ================= 解压 ================= */

    private fun unzip(
        zipFile: File,
        target: File,
        entries: List<java.util.zip.ZipEntry>,
        onProgress: (Float) -> Unit,
    ) {
        var total = 0L
        entries.forEach { total += it.size.coerceAtLeast(0) }
        if (total <= 0L) total = 1L

        ZipFile(zipFile).use { zf ->
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
                            FileOutputStream(out).use { output -> input.copyTo(output, 256 * 1024) }
                        }
                    }
                }
                done += entry.size.coerceAtLeast(0)
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /* ================= 符号链接 ================= */

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

    /* ================= 权限 ================= */

    private fun fixPermissions(prefix: File) {
        val execDirs = listOf("bin", "libexec", "libexec/termux-am")
        execDirs.forEach { d ->
            File(prefix, d).listFiles()?.forEach { f ->
                runCatching { Os.chmod(f.absolutePath, 0b111101101) }   // 0755
            }
        }
        prefix.walkTopDown().filter { it.isDirectory }.forEach {
            runCatching { Os.chmod(it.absolutePath, 0b111101101) }
        }
        File(prefix, "lib").listFiles()?.forEach {
            runCatching { Os.chmod(it.absolutePath, 0b110100100) }      // 0644
        }
    }

    /* ================= 路径重写 ================= */

    private suspend fun rewriteAll(ctx: Context, prefix: File, onProgress: (Float, String) -> Unit) {
        val newPrefix = prefix.absolutePath
        val newHome = home(ctx).absolutePath

        val files = runCatching {
            prefix.walkTopDown().filter { it.isFile }.toList()
        }.getOrDefault(emptyList())

        val oldPrefixBytes = OLD_PREFIX.toByteArray(Charsets.UTF_8)
        var touched = 0
        var skippedBig = 0
        var i = 0

        files.forEach { f ->
            i++
            if (i % 60 == 0) {
                onProgress(
                    0.90f + 0.09f * i / files.size.coerceAtLeast(1),
                    "重写路径 " + i + "/" + files.size
                )
            }
            val len = runCatching { f.length() }.getOrDefault(0L)
            if (len <= 0L) return@forEach
            if (len > MAX_REWRITE_BYTES) { skippedBig++; return@forEach }

            val head = ByteArray(4)
            val isElf = runCatching {
                f.inputStream().use { it.read(head) }
                head.size == 4 && head[0] == 0x7f.toByte() &&
                    head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
                    head[3] == 'F'.code.toByte()
            }.getOrDefault(false)

            if (isElf) {
                if (rewriteBinary(f, oldPrefixBytes, newPrefix, OLD_HOME.toByteArray(), newHome)) touched++
            } else {
                if (rewriteText(f, newPrefix, newHome)) touched++
            }
        }

        onProgress(0.995f, "改过 " + touched + " 个文件" + if (skippedBig > 0) "（跳过 " + skippedBig + " 个大文件）" else "")
    }

    /**
     * 二进制原地替换：新路径更短，后面补 \0。
     * 文件长度不变，ELF 里所有偏移都不用动。
     */
    private fun rewriteBinary(file: File, oldPrefix: ByteArray, newPrefix: String, oldHome: ByteArray, newHome: String): Boolean {
        return runCatching {
            val bytes = file.readBytes()
            var changed = false

            fun patch(old: ByteArray, new: String) {
                val nb = new.toByteArray(Charsets.UTF_8)
                if (nb.size > old.size) return                  // 新路径更长就放弃，免得踩坏偏移
                val padded = ByteArray(old.size)
                System.arraycopy(nb, 0, padded, 0, nb.size)
                var idx = indexOf(bytes, old)
                while (idx >= 0) {
                    System.arraycopy(padded, 0, bytes, idx, old.size)
                    changed = true
                    idx = indexOf(bytes, old, idx + old.size)
                }
            }

            patch(oldPrefix, newPrefix)
            patch(oldHome, newHome)

            if (changed) file.writeBytes(bytes)
            changed
        }.getOrDefault(false)
    }

    /** 纯文本（shebang / 配置）直接整串替换，这些不用保长度 */
    private fun rewriteText(file: File, newPrefix: String, newHome: String): Boolean {
        return runCatching {
            val len = file.length()
            if (len <= 0L || len > 2L * 1024 * 1024) return false

            val probe = ByteArray(minOf(256, len.toInt()))
            file.inputStream().use { it.read(probe) }
            if (probe.any { it == 0.toByte() }) return false    // 带 NUL 的当二进制看

            val text = file.readText()
            if (!text.contains(OLD_PREFIX) && !text.contains(OLD_HOME)) return false
            file.writeText(text.replace(OLD_PREFIX, newPrefix).replace(OLD_HOME, newHome))
            true
        }.getOrDefault(false)
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

    /* ================= 环境 ================= */

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
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "EXTERNAL_STORAGE=/storage/emulated/0",
        )
    }

    fun shellPath(ctx: Context): String =
        if (isInstalled(ctx)) File(prefix(ctx), "bin/bash").absolutePath else "/system/bin/sh"

    fun uninstall(ctx: Context): Boolean =
        runCatching { prefix(ctx).deleteRecursively() }.getOrDefault(false)

    /** 自己体检一下，给界面显示 */
    fun diagnose(ctx: Context): List<Pair<String, Boolean>> = listOf(
        "环境已装" to isInstalled(ctx),
        "bin/bash 在" to File(prefix(ctx), "bin/bash").exists(),
        "bin/sh 在" to File(prefix(ctx), "bin/sh").exists(),
        "lib 在" to File(prefix(ctx), "lib").isDirectory,
        "缓存里有包" to cachedZip(ctx).exists(),
    )
}
