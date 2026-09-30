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
 * shebang 和不少脚本里全是这个硬编码路径。我们包名是 lo.naui，
 * 路径一变就找不到解释器 / 库。这些字符串在 .dynstr 和文本里都是 null 结尾的，
 * 换成更短的新路径、后面补 \0，偏移不用动，于是不用重编也不用改包名。
 *
 * --- 为什么要有「从本地 zip 安装」---
 * 国内直连 github 经常中途断流。断了会留下半个包，
 * 而半个包是**打不开**的（zip 的中央目录在文件末尾）。
 * 所以这里做三件事：下完必须能校验通过 / 缓存里的坏包自动丢掉 / 还能自己喂 zip。
 *
 * --- 踩过的坑（都是实机反馈回来的）---
 *  1. SYMLINKS.txt 的分隔符是**单个 `←`**（U+2190），
 *     不是 `←→`。写错了会导致符号链接一个都建不起来。
 *  2. 下载中断留下的残包要**校验完整性**再用，光看大小会被半个包骗过去，
 *     表现就是一直报 "zip END header not found" 死循环。
 *  3. 解压先落到 `usr.staging`，全部弄好再 rename 成 `usr`，
 *     免得失败时留下一个半成品把下次安装堵死。
 */
object Bootstrap {

    private const val OLD_PREFIX = "/data/data/com.termux/files/usr"
    private const val OLD_HOME = "/data/data/com.termux/files/home"

    /** termux 自带的 SYMLINKS.txt 就用这一个字符分隔（源码里是 split("←")） */
    private const val SYMLINK_SEP = "←"

    /**
     * 单个文件重写的上限。
     * 整读进内存再写回的方式，遇到 30MB+ 的文件手机上直接 OOM，
     * 所以只碰 8MB 以内的，而且先看文件头是不是 ELF。
     */
    private const val MAX_REWRITE_BYTES = 8L * 1024 * 1024

    /** 一个像样的 bootstrap 至少这么多条目 */
    private const val MIN_ZIP_ENTRIES = 50

    /** 实测过的能用的源，按速度排 */
    private fun mirrors(arch: String): List<String> {
        val gh = "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-$arch.zip"
        return listOf(
            gh,                                  // 直连
            "https://ghfast.top/$gh",
            "https://ghproxy.net/$gh",
            "https://gh-proxy.com/$gh",
            "https://ghproxy.link/$gh",
        )
    }

    fun prefix(ctx: Context): File = File(ctx.filesDir, "usr")

    /** 解压先用它，成功了再改名成 usr */
    private fun staging(ctx: Context): File = File(ctx.filesDir, "usr.staging")

    fun home(ctx: Context): File = File(ctx.filesDir, "home")

    fun cachedZip(ctx: Context): File = File(ctx.cacheDir, "bootstrap.zip")

    fun arch(): String = when {
        android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("armeabi") } -> "arm"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "x86_64"
        else -> "i686"
    }

    fun manualUrl(): String =
        "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-" + arch() + ".zip"

    /* ================= 遍历 ================= */

    /** 是不是符号链接 */
    private fun isLink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /**
     * 安全地遍历。
     *
     * bootstrap 里有指向目录的链接（比如 bin/xxx → ../libexec），
     * 直接 walkTopDown 会跟进去，运气不好就绕成环卡死。
     * 所以碰到链接就不进、也不当普通文件处理。
     */
    private fun walkFiles(root: File): List<File> = runCatching {
        root.walkTopDown()
            .onEnter { dir -> dir == root || !isLink(dir) }
            .filter { it.isFile && !isLink(it) }
            .toList()
    }.getOrDefault(emptyList())

    /* ================= 状态判定 ================= */

    fun isInstalled(ctx: Context): Boolean {
        val bin = File(prefix(ctx), "bin")
        if (!bin.isDirectory) return false
        return File(bin, "bash").exists() || File(bin, "sh").exists()
    }

    /**
     * 这个 zip 是不是一个**完整的** bootstrap。
     *
     * 光看大小是不够的：断流留下的半个包可能有好几 MB，
     * 但它末尾没有中央目录，ZipFile 直接抛 "zip END header not found"。
     * 所以这里真的去打开它，并且要求里面有 SYMLINKS.txt。
     */
    fun isValidZip(f: File): Boolean = runCatching {
        if (!f.exists() || f.length() < 1024L * 1024L) return false
        ZipFile(f).use { zf ->
            var hasSymlinks = false
            var count = 0
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val en = e.nextElement()
                count++
                if (en.name == "SYMLINKS.txt") hasSymlinks = true
            }
            hasSymlinks && count >= MIN_ZIP_ENTRIES
        }
    }.getOrDefault(false)

    fun installedSize(ctx: Context): Long =
        walkFiles(prefix(ctx)).sumOf { runCatching { it.length() }.getOrDefault(0L) }

    fun diagnose(ctx: Context): List<Pair<String, Boolean>> {
        val zip = cachedZip(ctx)
        return listOf(
            "环境已装" to isInstalled(ctx),
            "bin/bash 在" to File(prefix(ctx), "bin/bash").exists(),
            "bin/sh 在" to File(prefix(ctx), "bin/sh").exists(),
            "lib 在" to File(prefix(ctx), "lib").isDirectory,
            "缓存包存在" to zip.exists(),
            "缓存包完整" to isValidZip(zip),
        )
    }

    /* ================= 入口 ================= */

    suspend fun install(
        ctx: Context,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = cachedZip(ctx)
            if (isValidZip(zip)) {
                onProgress(0.45f, "用上次下好的包（校验通过）")
            } else {
                if (zip.exists()) {
                    onProgress(0.02f, "缓存里那个包是坏的，丢掉重下")
                    runCatching { zip.delete() }
                }
                downloadAny(ctx, zip, onProgress)
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
            if (!isValidZip(src)) {
                throw IllegalStateException("这个文件不是完整的 bootstrap zip（打不开，或者里面没有 SYMLINKS.txt）")
            }
            val zip = cachedZip(ctx)
            runCatching { zip.delete() }
            src.copyTo(zip, overwrite = true)
            installFrom(ctx, zip, onProgress)
        }
    }

    /* ================= 安装 ================= */

    private suspend fun installFrom(
        ctx: Context,
        zip: File,
        onProgress: (Float, String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        onProgress(0.46f, "校验压缩包")
        if (!isValidZip(zip)) {
            throw IllegalStateException(
                "这个 zip 打不开，可能没下完（zip END header not found）\n" +
                    "已经把它删了，再点一次「在线装」会重新下"
            )
        }

        // 空间：解压后大概 200~250MB
        val need = 300L * 1024 * 1024
        val free = runCatching { ctx.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free in 1 until need) {
            throw IllegalStateException(
                "空间不够：还要约 " + (need / 1024 / 1024) + "MB，只剩 " + (free / 1024 / 1024) + "MB"
            )
        }

        val stage = staging(ctx)
        val finalPrefix = prefix(ctx)
        stage.deleteRecursively()
        stage.mkdirs()

        val entries = runCatching { ZipFile(zip).use { it.entries().toList() } }
            .getOrElse { throw IllegalStateException("读不了这个 zip：" + (it.message ?: "")) }

        onProgress(0.50f, "解压 " + entries.size + " 个文件")
        unzip(zip, stage, entries) { p -> onProgress(0.50f + p * 0.28f, "解压中") }

        onProgress(0.80f, "重建符号链接")
        val links = fixSymlinks(ctx, stage)
        if (links == 0) {
            stage.deleteRecursively()
            throw IllegalStateException("这个包里没有 SYMLINKS.txt —— 不是 termux 的 bootstrap")
        }

        onProgress(0.84f, "就位（" + links + " 个链接）")
        finalPrefix.deleteRecursively()
        if (!stage.renameTo(finalPrefix)) {
            stage.deleteRecursively()
            throw IllegalStateException("把解压好的目录改名成 usr 失败")
        }

        onProgress(0.88f, "设可执行权限")
        fixPermissions(finalPrefix)

        onProgress(0.90f, "重写内嵌路径（termux → 本应用）")
        rewriteAll(ctx, finalPrefix, onProgress)

        if (!isInstalled(ctx)) {
            throw IllegalStateException(
                "解压完了但 bin/ 里没有 bash 或 sh，这个包可能不对"
            )
        }

        // 装完就把源换成国内的 —— termux 默认那批欧洲镜像在国内全是 bad，
        // 不换的话 pkg update 一上来就 "None of the mirrors are accessible"
        onProgress(0.999f, "换成国内源（" + DEFAULT_MIRROR.label + "）")
        setMirror(ctx, DEFAULT_MIRROR)

        onProgress(1f, "装好了 · 源已设为" + DEFAULT_MIRROR.label)
    }

    /* ================= 下载 ================= */

    private suspend fun downloadAny(ctx: Context, out: File, onProgress: (Float, String) -> Unit) {
        val arch = arch()
        val list = mirrors(arch)
        var last = ""

        list.forEachIndexed { i, url ->
            val host = url.substringAfter("//").substringBefore('/').take(26)
            // 同一个源试两次，网络抖一下不至于直接放弃
            repeat(2) { attempt ->
                onProgress(0.02f, "试第 " + (i + 1) + "/" + list.size + " 个源：" + host +
                    if (attempt == 1) "（重试）" else "")
                val r = runCatching {
                    download(url, out) { p ->
                        onProgress(0.02f + p * 0.42f, "下载中 " + host + " " + (p * 100).toInt() + "%")
                    }
                    if (!isValidZip(out)) {
                        throw IllegalStateException("下到的是坏包（多半中途断流了）")
                    }
                }
                if (r.isSuccess) return
                last = host + "：" + (r.exceptionOrNull()?.message ?: "")
                runCatching { out.delete() }
            }
        }

        throw IllegalStateException(
            "所有源都没下成功（最后试的是 " + last + "）\n\n" +
                "两个办法：\n" +
                "① 用浏览器打开下面这个地址，下好 zip，再点「从文件装」选它：\n" +
                manualUrl() + "\n\n" +
                "② 或者把 zip 放到这个路径，再点「在线装」：\n" +
                cachedZip(ctx).absolutePath
        )
    }

    private fun download(url: String, out: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 40_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nakour")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP " + code)
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (33L * 1024 * 1024)
        out.parentFile?.mkdirs()
        var done = 0L

        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(256 * 1024)
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

        // 断流时 read 会返回 -1，看起来像"正常结束"——
        // 所以这里必须拿实际字节数跟声明的大小比一次
        if (total > 0 && done < total - 1024) {
            throw IllegalStateException("下到 " + (done / 1024 / 1024) + "MB 就断了（应为 " + (total / 1024 / 1024) + "MB）")
        }
        if (out.length() < 1024L * 1024L) {
            throw IllegalStateException("只有 " + (out.length() / 1024) + "KB，不是 bootstrap")
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

    /**
     * 读 SYMLINKS.txt 补符号链接，返回建了几个。
     *
     * **分隔符是单个 `←`**（termux 源码里就是 `line.split("←")`）。
     * 这里同时把目标里的旧前缀换成我们的最终路径 —— 因为马上要把
     * staging 改名成 usr，链接得提前指向最终位置。
     */
    private fun fixSymlinks(ctx: Context, stage: File): Int {
        val f = File(stage, "SYMLINKS.txt")
        if (!f.exists()) return 0
        val finalPrefix = prefix(ctx).absolutePath
        val finalHome = home(ctx).absolutePath
        var made = 0

        runCatching {
            f.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split(SYMLINK_SEP)
                if (parts.size != 2) return@forEach
                val rawTarget = parts[0].trim()
                val linkRel = parts[1].trim()
                if (linkRel.isBlank()) return@forEach

                val target = rawTarget.replace(OLD_PREFIX, finalPrefix).replace(OLD_HOME, finalHome)
                val link = File(stage, linkRel)
                link.parentFile?.mkdirs()
                runCatching {
                    if (link.exists()) link.delete()
                    Os.symlink(target, link.absolutePath)
                    made++
                }
            }
            f.delete()
        }
        return made
    }

    /* ================= 权限 ================= */

    private fun fixPermissions(prefix: File) {
        listOf("bin", "libexec", "libexec/termux-am").forEach { d ->
            File(prefix, d).listFiles()?.forEach { f ->
                runCatching { Os.chmod(f.absolutePath, 0b111101101) }   // 0755
            }
        }
        prefix.walkTopDown()
            .onEnter { dir -> dir == prefix || !isLink(dir) }
            .filter { it.isDirectory && !isLink(it) }
            .forEach { runCatching { Os.chmod(it.absolutePath, 0b111101101) } }
        File(prefix, "lib").listFiles()?.forEach {
            runCatching { Os.chmod(it.absolutePath, 0b110100100) }      // 0644
        }
    }

    /* ================= 路径重写 ================= */

    private suspend fun rewriteAll(ctx: Context, prefix: File, onProgress: (Float, String) -> Unit) {
        val newPrefix = prefix.absolutePath
        val newHome = home(ctx).absolutePath
        val oldPrefixBytes = OLD_PREFIX.toByteArray(Charsets.UTF_8)
        val oldHomeBytes = OLD_HOME.toByteArray(Charsets.UTF_8)

        val files = walkFiles(prefix)

        var touched = 0
        var skippedBig = 0
        var i = 0

        files.forEach { f ->
            i++
            if (i % 60 == 0) {
                onProgress(0.90f + 0.09f * i / files.size.coerceAtLeast(1), "重写路径 " + i + "/" + files.size)
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
                if (rewriteBinary(f, oldPrefixBytes, newPrefix, oldHomeBytes, newHome)) touched++
            } else {
                if (rewriteText(f, newPrefix, newHome)) touched++
            }
        }

        onProgress(0.998f, "改过 " + touched + " 个文件" + if (skippedBig > 0) "（跳过 " + skippedBig + " 个大文件）" else "")
    }

    /** 二进制原地替换：新路径更短，后面补 \0，文件长度不变 */
    private fun rewriteBinary(
        file: File,
        oldPrefix: ByteArray,
        newPrefix: String,
        oldHome: ByteArray,
        newHome: String,
    ): Boolean = runCatching {
        val bytes = file.readBytes()
        var changed = false

        fun patch(old: ByteArray, new: String) {
            val nb = new.toByteArray(Charsets.UTF_8)
            if (nb.size > old.size) return
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

    /** 纯文本（shebang / 配置）整串替换，这些不用保长度 */
    private fun rewriteText(file: File, newPrefix: String, newHome: String): Boolean = runCatching {
        val len = file.length()
        if (len <= 0L || len > 2L * 1024 * 1024) return false

        val probe = ByteArray(minOf(256, len.toInt()))
        file.inputStream().use { it.read(probe) }
        if (probe.any { it == 0.toByte() }) return false

        val text = file.readText()
        if (!text.contains(OLD_PREFIX) && !text.contains(OLD_HOME)) return false
        file.writeText(text.replace(OLD_PREFIX, newPrefix).replace(OLD_HOME, newHome))
        true
    }.getOrDefault(false)

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

    fun uninstall(ctx: Context): Boolean = runCatching {
        prefix(ctx).deleteRecursively() and staging(ctx).deleteRecursively()
    }.getOrDefault(false)

    /* ================= 换源 ================= */

    data class Mirror(val id: String, val label: String, val url: String)

    val MIRRORS = listOf(
        Mirror("official", "官方", "https://packages.termux.dev/apt/termux-main"),
        Mirror("tuna", "清华", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
        Mirror("ustc", "中科大", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
        Mirror("bfsu", "北外", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"),
        Mirror("nju", "南大", "https://mirror.nju.edu.cn/termux/apt/termux-main"),
        Mirror("sjtu", "上交", "https://mirror.sjtu.edu.cn/termux/apt/termux-main"),
        Mirror("zju", "浙大", "https://mirrors.zju.edu.cn/termux/apt/termux-main"),
    )

    /** 国内优先，装完默认就用它 */
    val DEFAULT_MIRROR: Mirror get() = MIRRORS[1]     // 清华

    /**
     * 写 sources.list。
     *
     * 只写 main —— termux 的默认 sources.list 里塞了一堆欧洲镜像，
     * apt 会挨个去试，国内全是 bad，最后报 "None of the mirrors are accessible"。
     * 换成一个国内的，一条就通。
     */
    suspend fun setMirror(ctx: Context, mirror: Mirror): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val etc = File(prefix(ctx), "etc/apt").apply { mkdirs() }
            File(etc, "sources.list").writeText("deb " + mirror.url + " stable main\n")
            // 顺手清掉可能存在的其它 list，免得 apt 又去试国外的
            runCatching {
                File(etc, "sources.list.d").listFiles()?.forEach { it.delete() }
            }
        }
    }

    /** 现在用的是哪家（读 sources.list 认一下） */
    fun currentMirror(ctx: Context): Mirror? {
        val f = File(prefix(ctx), "etc/apt/sources.list")
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrDefault("")
        return MIRRORS.firstOrNull { text.contains(it.url) }
    }

    /** 换完源跑一次 apt update，把结果原样返回给界面看 */
    suspend fun aptUpdate(ctx: Context): String = withContext(Dispatchers.IO) {
        val bash = File(prefix(ctx), "bin/bash")
        if (!bash.exists()) return@withContext "环境没装"
        runCatching {
            val pb = ProcessBuilder(bash.absolutePath, "-lc", "apt update 2>&1 | tail -n 25")
            pb.environment().clear()
            environ(ctx).forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            val p = pb.redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.ifBlank { "（没输出）" }
        }.getOrElse { "跑不起来：" + (it.message ?: it.javaClass.simpleName) }
    }

    /** 直接拿它跑一条命令，用来验证环境到底能不能用 */
    suspend fun selfTest(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!isInstalled(ctx)) return@withContext "环境没装"
        val bash = File(prefix(ctx), "bin/bash").absolutePath
        val env = environ(ctx)
        runCatching {
            val pb = ProcessBuilder(bash, "-c", "echo OK; uname -m; echo \$PREFIX")
            pb.environment().clear()
            env.forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            val p = pb.redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrElse { "跑不起来：" + (it.message ?: it.javaClass.simpleName) }
    }
}
