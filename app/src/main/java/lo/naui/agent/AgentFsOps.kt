package lo.naui.agent

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Agent 的"动手"层：文件操作 / 目录树 / 找文件 / 搜内容 / stat / 压缩包 / 元信息。
 *
 * ## 为什么要这一层
 *
 * 以前 AI 只有 run_shell，动文件全靠拼字符串：`cp -a` 还是 `cp -r`、带空格的路径
 * 要不要引号、`rm -rf` 手一抖就出沙箱。更要命的是**拼出来的命令不走路径围栏** ——
 * resolve() 只在 read_file / write_file 那条路上生效，shell 那边只有"cd 到沙箱根
 * + 带 .. 就提示一句"的软围栏，所以说"沙箱"对这类操作其实是虚的。
 *
 * 这一层每个操作都先过 [AgentRunner.guardPath]（跟 read/write 同一个 resolve），
 * 解不出来就直接拒 —— 这才是"使沙箱真正可用"。
 *
 * ## find / grep 也不再赌 shell
 *
 * 原来这两条是拼 `find -name` / `grep -rn` 交给 shell 跑的：没装 Termux 就得赌
 * /system/bin 里有没有 toybox，赌输了模型只收到一句"执行失败"，然后开始换写法硬撞。
 * 现在纯 java.io 遍历，不依赖任何外部二进制。
 *
 * ## 7z
 *
 * 1.02.0 起支持（用户批准加了 commons-compress + tukaani-xz 两个依赖）。
 * 里面的加密条目解不开会照实报"需要密码"，不让模型把"解不开"当成"我命令写错了"。
 */
object AgentFsOps {

    /* ---------------- 围栏 ---------------- */

    private fun guardOne(ctx: Context, env: AgentEnv, path: String): String? =
        AgentRunner.guardPath(ctx, env, path.trim())

    private fun guardTwo(ctx: Context, env: AgentEnv, a: String, b: String): Pair<String, String>? {
        val x = guardOne(ctx, env, a) ?: return null
        val y = guardOne(ctx, env, b) ?: return null
        return x to y
    }

    private fun outside(path: String) =
        "这个路径在沙箱外面：" + path +
            "（要动外面的东西，让用户在执行环境那个弹窗里切成「本机（root）」）"

    /* ---------------- copy / move / rename / mkdir ---------------- */

    /**
     * 复制或移动。rename 就是同目录下的 move —— 不再单开一个工具，
     * 少一个工具就少一截每次请求都要发的 schema。
     */
    suspend fun copyOrMove(
        ctx: Context,
        env: AgentEnv,
        from: String,
        to: String,
        copy: Boolean,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (from.isBlank() || to.isBlank()) {
            return@withContext ToolResult(false, "from 和 to 都得给：" + if (copy) "复制" else "移动")
        }
        val g = guardTwo(ctx, env, from, to)
            ?: return@withContext ToolResult(false, outside(if (from.isBlank()) to else from))
        runCatching {
            val src = File(g.first)
            val dst = File(g.second)
            if (!src.exists()) return@runCatching ToolResult(false, "源不存在：" + src.path)
            if (src.isDirectory && src.canonicalPath.let { dst.canonicalPath == it || dst.canonicalPath.startsWith("$it/") }) {
                return@runCatching ToolResult(false, "不能把目录移到它自己里面去：" + src.path + " → " + dst.path)
            }
            if (dst.exists() && dst.isDirectory) {
                return@runCatching ToolResult(
                    false, "目标是已存在的目录，请写清落到它下面叫什么：" + dst.path + "/" + src.name
                )
            }
            if (dst.canonicalPath == src.canonicalPath) {
                return@runCatching ToolResult(false, "源和目标是同一个东西：" + src.path)
            }
            dst.parentFile?.mkdirs()
            if (src.isDirectory) {
                copyTree(src, dst)
                if (!copy) src.deleteRecursively()
            } else {
                FileInputStream(src).use { i -> FileOutputStream(dst).use { i.copyTo(it) } }
                if (!copy) src.delete()
            }
            val verb = if (copy) "拷好了" else if (src.parent == dst.parent) "改好名/挪好了" else "挪好了"
            ToolResult(true, verb + "：" + src.path + " → " + dst.path)
        }.getOrElse { ToolResult(false, (if (copy) "复制失败：" else "移动失败：") + (it.message ?: "")) }
    }

    private fun copyTree(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { c ->
            val t = File(to, c.name)
            if (c.isDirectory) copyTree(c, t)
            else FileInputStream(c).use { i -> FileOutputStream(t).use { i.copyTo(it) } }
        }
    }

    /** 建目录（父级一并建） */
    suspend fun mkdir(ctx: Context, env: AgentEnv, path: String): ToolResult =
        withContext(Dispatchers.IO) {
            val p = guardOne(ctx, env, path) ?: return@withContext ToolResult(false, outside(path))
            runCatching {
                val f = File(p)
                val existed = f.exists()
                val ok = f.mkdirs() || f.isDirectory
                ToolResult(
                    ok,
                    when {
                        !existed && ok -> "建好了 $p"
                        existed -> "已经在了（没动它）：$p"
                        else -> "建目录失败，多半是没权限：$p"
                    },
                )
            }.getOrElse { ToolResult(false, "建目录失败：" + (it.message ?: "")) }
        }

    /**
     * 删除：**带回待批准决策，用户点同意才真删**。
     *
     * 为什么比 shell 更严：工具化以后模型删起来更顺手，风险反而更高 ——
     * 它路径写错一片文件就没了，而且自己看不出来。
     * 删目录要求显式 recursive，少写就只让删单文件。
     */
    suspend fun delete(
        ctx: Context,
        env: AgentEnv,
        path: String,
        recursive: Boolean,
    ): ToolResult = withContext(Dispatchers.IO) {
        val p = guardOne(ctx, env, path) ?: return@withContext ToolResult(false, outside(path))
        val target = File(p)
        if (!target.exists()) return@withContext ToolResult(false, "没有这个东西：" + p)
        val isDir = target.isDirectory
        if (isDir && !recursive) {
            return@withContext ToolResult(
                false,
                "那是个目录：" + p + "（" + (target.list()?.size ?: 0) + " 个直接子项）。" +
                    "要整个删得显式给 recursive=true —— 先想清楚里面还有什么。",
            )
        }
        val files = if (isDir) target.walkTopDown().count { it.isFile } else 1
        val size = if (isDir) dirSize(target) else target.length()
        ToolResult(
            ok = false,
            output = "删除还没执行，等用户同意；同意后会自动完成，不用你再发一遍。",
            decision = RiskDecision(
                kind = RiskKind.Delete,
                detail = p + (if (isDir) "/" else ""),
                note = if (isDir) "目录，含 $files 个文件，共 ${human(size)}" else "文件 ${human(size)}",
                consequence = "删掉基本找不回来" + if (isDir) "（这一整个子树 $files 个文件）" else "，确认路径没写错。",
                run = {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val gone = if (isDir) target.deleteRecursively() else target.delete()
                            ToolResult(
                                gone,
                                if (gone) "删掉了 $p" else "删失败（还有权限/占用问题？）：$p",
                            )
                        }.getOrElse { ToolResult(false, "删除失败：" + (it.message ?: "")) }
                    }
                },
            ),
        )
    }

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

    /* ---------------- 目录树 ---------------- */

    /**
     * 一次给一棵树。
     *
     * 为什么需要：以前模型想知道目录结构只能一层层 list_dir，五层要问五次，
     * 每次都是"发请求 → 模型读 → 再发"。深度和节点数封顶，防着一口气把整个
     * /sdcard 吐回上下文把窗口撑爆。
     */
    suspend fun tree(
        ctx: Context,
        env: AgentEnv,
        path: String,
        depth: Int,
        maxNodes: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        val p = guardOne(ctx, env, path.ifBlank { "." })
            ?: return@withContext ToolResult(false, outside(path))
        val root = File(p)
        if (!root.exists()) return@withContext ToolResult(false, "没有这个目录：" + p)
        if (!root.isDirectory) return@withContext ToolResult(false, "那是文件不是目录：" + p)
        val cap = maxNodes.coerceIn(10, 3000)
        val dmax = depth.coerceIn(1, 6)
        val sb = StringBuilder()
        var nodes = 1
        var cut = false
        sb.append(p).append("/\n")

        fun walk(dir: File, pad: String, lvl: Int) {
            val kids = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) }
            )
            if (kids == null) {
                sb.append(pad).append("（读不了：权限不够）\n")
                return
            }
            kids.forEachIndexed { i, c ->
                if (nodes >= cap) {
                    cut = true
                    return@forEachIndexed
                }
                nodes++
                val last = i == kids.size - 1
                sb.append(pad).append(if (last) "└─ " else "├─ ").append(c.name)
                if (c.isDirectory) {
                    sb.append("/（").append(c.list()?.size ?: 0).append(" 项）\n")
                    if (lvl < dmax) walk(c, pad + if (last) "   " else "│  ", lvl + 1)
                } else {
                    sb.append("  ").append(human(c.length())).append('\n')
                }
            }
        }
        walk(root, "", 1)
        if (cut) sb.append("…（到 ").append(cap).append(" 项截断了；要更深就从子目录再问一次）")
        ToolResult(true, if (nodes <= 1) "（空目录）" else sb.toString())
    }

    /* ---------------- 找文件 / 搜内容 ---------------- */

    /**
     * 按文件名找。支持 * 和 ? 通配。
     *
     * 找不到时把"扫了多少个条目"一起回 —— 这句是关键：
     * 只回"没找到"，模型会以为确实没有，其实是目录选错了或者权限读不了。
     */
    suspend fun find(
        ctx: Context,
        env: AgentEnv,
        root: String,
        namePattern: String,
        maxDepth: Int,
        limit: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        val base = guardOne(ctx, env, root.ifBlank { "." })
            ?: return@withContext ToolResult(false, outside(root))
        val start = File(base)
        if (!start.exists()) return@withContext ToolResult(false, "没有这个目录：" + base)
        val matcher = globMatcher(namePattern.ifBlank { "*" })
        val dmax = maxDepth.coerceIn(1, 12)
        val cap = limit.coerceIn(10, 500)
        val sb = StringBuilder()
        var hits = 0
        var scanned = 0
        var blocked = 0

        fun walk(dir: File, depth: Int) {
            if (hits >= cap || depth > dmax) return
            val kids = dir.listFiles()
            if (kids == null) {
                blocked++
                return
            }
            kids.forEach { c ->
                scanned++
                if (matcher(c.name) && hits < cap) {
                    hits++
                    sb.append(c.path)
                        .append(if (c.isDirectory) "   /" else "   " + human(c.length()))
                        .append('\n')
                }
                if (c.isDirectory) walk(c, depth + 1)
            }
        }
        if (start.isFile) return@withContext ToolResult(true, start.path)
        walk(start, 1)
        if (hits == 0) {
            return@withContext ToolResult(
                true,
                "（没找到匹配 \"" + namePattern + "\" 的东西。扫了 $scanned 个条目" +
                    (if (blocked > 0) "，其中 $blocked 个目录读不了（权限）" else "") +
                    "。可以换个更宽的通配符，或者往上层目录再找）",
            )
        }
        ToolResult(
            true,
            sb.toString() + "\n共 $hits 个（扫了 $scanned 个条目" +
                (if (hits >= cap) "，到 $cap 个截断 —— 要更多把 limit 调大" else "") + "）",
        )
    }

    /**
     * 在文件内容里搜。三个实用增强：
     *   · [context] 带上下几行 —— 以前只回一行命中，模型还得再 read_file 才知道上下文，
     *     一个来回就这么没了；
     *   · [ignoreCase]；
     *   · [limit] 截断时**明说截断了** —— 不说的话它会以为搜完了。
     */
    suspend fun grep(
        ctx: Context,
        env: AgentEnv,
        root: String,
        pattern: String,
        fileGlob: String,
        context: Int,
        ignoreCase: Boolean,
        limit: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (pattern.isBlank()) return@withContext ToolResult(false, "pattern 是空的。")
        val base = guardOne(ctx, env, root.ifBlank { "." })
            ?: return@withContext ToolResult(false, outside(root))
        val start = File(base)
        if (!start.exists()) return@withContext ToolResult(false, "没有这个目录：" + base)
        val matchName = globMatcher(fileGlob.ifBlank { "*" })
        val ctxLines = context.coerceIn(0, 4)
        val cap = limit.coerceIn(5, 200)
        val needle = if (ignoreCase) pattern.lowercase(Locale.US) else pattern
        val sb = StringBuilder()
        var hits = 0
        var filesScanned = 0
        var binarySkipped = 0

        fun scan(file: File) {
            if (hits >= cap) return
            if (file.length() > 4_000_000L) return
            val lines = runCatching {
                FileInputStream(file).use { it.bufferedReader().readLines() }
            }.getOrNull() ?: return
            if (lines.any { it.indexOf('\u0000') >= 0 }) {
                binarySkipped++
                return
            }
            filesScanned++
            lines.forEachIndexed { i, raw ->
                if (hits >= cap) return
                val hay = if (ignoreCase) raw.lowercase(Locale.US) else raw
                if (!hay.contains(needle)) return@forEachIndexed
                hits++
                sb.append(file.path).append(':').append(i + 1).append(": ").append(raw.trim()).append('\n')
                if (ctxLines > 0) {
                    for (j in maxOf(0, i - ctxLines) until minOf(lines.size, i + ctxLines + 1)) {
                        if (j == i) continue
                        sb.append("      ").append(j + 1).append("- ").append(lines[j].trim()).append('\n')
                    }
                    sb.append('\n')
                }
            }
        }

        fun walk(dir: File, depth: Int) {
            if (hits >= cap || depth > 8) return
            val kids = dir.listFiles() ?: run { binarySkipped++; return }
            kids.forEach { c ->
                if (c.isDirectory) walk(c, depth + 1)
                else if (matchName(c.name)) scan(c)
            }
        }
        if (start.isFile) {
            if (matchName(start.name)) scan(start)
        } else walk(start, 1)

        if (hits == 0) {
            return@withContext ToolResult(
                true,
                "（没匹配到。扫了 $filesScanned 个文本文件" +
                    (if (binarySkipped > 0) "，跳过 $binarySkipped 个二进制/超大文件" else "") +
                    "。换个关键词、放宽 file_glob，或者确认搜的目录对不对）",
            )
        }
        ToolResult(
            true,
            sb.toString() + "\n命中 $hits 处" +
                (if (hits >= cap) "（到 $cap 处截断，剩下的没显示 —— 缩范围或调大 limit）" else ""),
        )
    }

    /** glob 转匹配器：只认 * 和 ?。不让模型写正则 —— 它写正则更容易错。 */
    private fun globMatcher(glob: String): (String) -> Boolean {
        val g = glob.trim()
        if (!g.contains('*') && !g.contains('?')) {
            return { name -> name.equals(g, ignoreCase = true) }
        }
        val regex = StringBuilder("^")
        var i = 0
        while (i < g.length) {
            when (val ch = g[i]) {
                '*' -> regex.append(".*")
                '?' -> regex.append('.')
                else -> regex.append(Regex.escape(ch.toString()))
            }
            i++
        }
        regex.append('$')
        val r = runCatching { Regex(regex.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return { name -> name.contains(g.trim('*')) }
        return { name -> r.containsMatchIn(name) || r.matches(name) }
    }

    /* ---------------- stat（顺手把图片 / APK 元信息一起给了）---------------- */

    suspend fun stat(ctx: Context, env: AgentEnv, path: String): ToolResult =
        withContext(Dispatchers.IO) {
            val p = guardOne(ctx, env, path) ?: return@withContext ToolResult(false, outside(path))
            val f = File(p)
            if (!f.exists()) return@withContext ToolResult(false, "没有这个东西：" + p)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val sb = StringBuilder()
            sb.append("路径：").append(p).append('\n')
            sb.append("类型：").append(if (f.isDirectory) "目录" else "文件").append('\n')
            sb.append("大小：").append(human(if (f.isDirectory) dirSize(f) else f.length())).append('\n')
            sb.append("改动：").append(fmt.format(Date(f.lastModified()))).append('\n')
            sb.append("权限：可读 ").append(f.canRead()).append(" 可写 ").append(f.canWrite()).append('\n')
            if (f.isDirectory) {
                val kids = f.listFiles()
                sb.append("直接子项：").append(kids?.size ?: 0)
                sb.append("（目录 ").append(kids?.count { it.isDirectory } ?: 0)
                    .append(" 文件 ").append(kids?.count { it.isFile } ?: 0).append("）\n")
                if (kids == null) sb.append("⚠️ 列不出来：权限不够\n")
            } else {
                if (isImageName(f.name)) {
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(p, opt)
                    if (opt.outWidth > 0 && opt.outHeight > 0) {
                        sb.append("图片：").append(opt.outWidth).append(" × ").append(opt.outHeight)
                            .append("，").append(opt.outMimeType ?: "格式未知").append('\n')
                    }
                }
                if (f.name.endsWith(".apk", true)) sb.append(apkInfoAt(ctx, p))
                if (looksText(f)) {
                    val lines = runCatching {
                        FileInputStream(f).use { it.bufferedReader().lines().count() }
                    }.getOrDefault(-1)
                    if (lines >= 0) sb.append("行数：").append(lines).append('\n')
                }
            }
            ToolResult(true, sb.toString())
        }

    private fun looksText(f: File): Boolean {
        if (f.length() == 0L) return true
        if (f.length() > 8_000_000L) return false
        return runCatching {
            val buf = ByteArray(1024)
            val n = FileInputStream(f).use { it.read(buf, 0, 1024) }
            n <= 0 || !buf.copyOf(n).contains(0.toByte())
        }.getOrDefault(false)
    }

    private fun isImageName(n: String): Boolean =
        n.endsWith(".png", true) || n.endsWith(".jpg", true) || n.endsWith(".jpeg", true) ||
            n.endsWith(".webp", true) || n.endsWith(".gif", true) || n.endsWith(".bmp", true) ||
            n.endsWith(".heic", true) || n.endsWith(".avif", true)

    /* ---------------- 压缩包 ---------------- */

    /** 打包成 zip */
    suspend fun zip(
        ctx: Context,
        env: AgentEnv,
        out: String,
        inputs: List<String>,
    ): ToolResult = withContext(Dispatchers.IO) {
        val outP = guardOne(ctx, env, out) ?: return@withContext ToolResult(false, outside(out))
        if (inputs.isEmpty()) return@withContext ToolResult(false, "inputs 是空的，要打包什么？")
        val srcs = inputs.mapNotNull { guardOne(ctx, env, it) }
        if (srcs.size != inputs.size) {
            return@withContext ToolResult(false, "有路径在沙箱外面，整包都不做了：" + inputs)
        }
        runCatching {
            val f = File(outP)
            f.parentFile?.mkdirs()
            var n = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(f))).use { z ->
                srcs.forEach { one ->
                    val src = File(one)
                    if (src.isDirectory) {
                        val base = src.parentFile?.path ?: ""
                        src.walkTopDown().filter { it.isFile }.forEach { fileToAdd ->
                            val entryName = fileToAdd.path.removePrefix(base).removePrefix(File.separator)
                            z.putNextEntry(ZipEntry(entryName))
                            FileInputStream(fileToAdd).use { it.copyTo(z) }
                            z.closeEntry()
                            n++
                        }
                    } else {
                        z.putNextEntry(ZipEntry(src.name))
                        FileInputStream(src).use { it.copyTo(z) }
                        z.closeEntry()
                        n++
                    }
                }
            }
            ToolResult(true, "打包好了 " + outP + "（$n 个文件，${human(File(outP).length())}）")
        }.getOrElse { ToolResult(false, "打包失败：" + (it.message ?: "")) }
    }

    /** 列内容（不解压就能看有什么） */
    suspend fun archiveList(ctx: Context, env: AgentEnv, path: String): ToolResult =
        withContext(Dispatchers.IO) {
            val p = guardOne(ctx, env, path) ?: return@withContext ToolResult(false, outside(path))
            val f = File(p)
            if (!f.isFile) return@withContext ToolResult(false, "那不是文件：" + p)
            if (f.name.endsWith(".7z", true)) {
                return@withContext runCatching {
                    org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(f).get().use { z ->
                        val entries = z.entries.toList()
                        val sb = StringBuilder("共 ${entries.size} 项：\n")
                        entries.take(400).forEach { e ->
                            sb.append(if (e.isDirectory) "d " else "f ")
                                .append(human(e.size).padEnd(9))
                                .append(e.name)
                            if (e.isEncrypted()) sb.append("  🔒加密（解不开，需要密码）")
                            sb.append('\n')
                        }
                        if (entries.size > 400) sb.append("…（只显示前 400 项）")
                        ToolResult(true, sb.toString())
                    }
                }.getOrElse { ToolResult(false, "7z 读不出来：" + (it.message ?: it.javaClass.simpleName)) }
            }
            if (f.name.endsWith(".tar", true)) {
                return@withContext ToolResult(true, "tar 没有中央目录，列不了；直接 archive_extract 解出来再看。")
            }
            runCatching {
                java.util.zip.ZipFile(f).use { z ->
                    val all = z.entries().toList()
                    val sb = StringBuilder("共 ${all.size} 项：\n")
                    var i = 0
                    all.forEach { e ->
                        if (i++ >= 400) return@forEach
                        sb.append(if (e.isDirectory) "d " else "f ")
                            .append(human(e.size).padEnd(9))
                            .append(e.name).append('\n')
                    }
                    if (all.size > 400) sb.append("…（只显示前 400 项）")
                    ToolResult(true, sb.toString())
                }
            }.getOrElse {
                ToolResult(false, "当 zip 读不出来：" + (it.message ?: "") + "（支持 zip/jar/apk/whl/egg）")
            }
        }

    private const val REJECT_7Z =
        "7z 处理不了：项目里没引 LZMA 依赖（commons-compress / tukaani-xz 都没有），" +
            "我不会为了这一个格式偷偷加依赖。要么让用户换成 .zip，" +
            "要么走 run_shell 调 Termux 里装的 7z（前提是真装了，没装先看 build_env）。"

    /**
     * 解压。
     *
     * ★ **带 zip-slip 防护** —— 这类代码最容易漏的一条：
     * 恶意包里 entry 名写 `../../../system/xx`，直接 `File(outDir, entry.name)`
     * 就把文件写到 outDir **外面**去了，路径围栏当场白搭。
     * 所以每个 entry 都取 canonicalPath 比对前缀，不对就丢掉并在结果里报数量。
     */
    suspend fun extract(
        ctx: Context,
        env: AgentEnv,
        archive: String,
        outDir: String,
    ): ToolResult = withContext(Dispatchers.IO) {
        val srcP = guardOne(ctx, env, archive) ?: return@withContext ToolResult(false, outside(archive))
        val dstP = guardOne(ctx, env, outDir.ifBlank { "." })
            ?: return@withContext ToolResult(false, outside(outDir))
        val src = File(srcP)
        val dst = File(dstP)
        if (!src.isFile) return@withContext ToolResult(false, "那不是文件：" + srcP)
        if (src.name.endsWith(".7z", true)) {
            // 7z：同样要过 zip-slip 那道检查（entry 名可以写 ../../../）
            return@withContext runCatching {
                var n7 = 0
                var bad7 = 0
                var encrypted = 0
                org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(src).get().use { z ->
                    z.entries.forEach { e ->
                        if (e.isDirectory) return@forEach
                        if (e.isEncrypted()) {
                            encrypted++
                            return@forEach
                        }
                        val target = File(dst, e.name).canonicalFile
                        if (!inside(target.path, dstCanon)) {
                            bad7++
                            return@forEach
                        }
                        target.parentFile?.mkdirs()
                        val buf = ByteArray(65536)
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val r = z.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                            }
                        }
                        n7++
                    }
                }
                val tail = buildString {
                    if (bad7 > 0) append("（拦下 $bad7 个想写到外面的条目 —— zip-slip 防护）")
                    if (encrypted > 0) append("，另有 $encrypted 个加密条目解不开（需要密码）")
                }
                ToolResult(true, "解出 $n7 个文件到 " + dst.path + tail)
            }.getOrElse { ToolResult(false, "7z 解压失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
        dst.mkdirs()
        val dstCanon = dst.canonicalPath
        var files = 0
        var skipped = 0
        runCatching {
            when {
                src.name.endsWith(".tar.gz", true) || src.name.endsWith(".tgz", true) ->
                    GZIPInputStream(BufferedInputStream(FileInputStream(src))).use {
                        files = untar(it, dstCanon, dst) { skipped++ }
                    }

                src.name.endsWith(".tar", true) ->
                    BufferedInputStream(FileInputStream(src)).use { files = untar(it, dstCanon, dst) { skipped++ } }

                src.name.endsWith(".gz", true) -> {
                    val name = src.name.removeSuffix(".gz").ifBlank { "out" }
                    val safe = File(dst, name).canonicalFile
                    if (!inside(safe.path, dstCanon)) {
                        skipped++
                    } else {
                        safe.parentFile?.mkdirs()
                        GZIPInputStream(FileInputStream(src)).use { i ->
                            FileOutputStream(safe).use { i.copyTo(it) }
                        }
                        files = 1
                    }
                }

                else -> ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val target = File(dst, e.name).canonicalFile
                        if (!inside(target.path, dstCanon)) {
                            skipped++   // zip-slip：这个 entry 想跑到外面，丢掉
                            zin.closeEntry()
                            continue
                        }
                        if (e.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { zin.copyTo(it) }
                            files++
                        }
                        zin.closeEntry()
                    }
                }
            }
            ToolResult(
                true,
                "解出 " + files + " 个文件到 " + dst.path +
                    (if (skipped > 0) "（另拦下 $skipped 个想写到外面的条目 —— zip-slip 防护）" else ""),
            )
        }.getOrElse { ToolResult(false, "解压失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun inside(path: String, rootCanon: String): Boolean =
        path == rootCanon || path.startsWith(rootCanon + File.separator)

    /**
     * tar 手写（512 字节定长头），不引第三方库。
     * 认 ustar 的 name+prefix 拼接；目录/普通文件够用了，
     * 符号链接和长文件名（GNU longname）直接跳过并在结果里计入 skipped。
     */
    private fun untar(
        input: java.io.InputStream,
        dstCanon: String,
        dst: File,
        onSkip: () -> Unit,
    ): Int {
        var count = 0
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) return count
            if (header.all { it == 0.toByte() }) return count
            val name = parseString(header, 0, 100)
            val prefix = parseString(header, 345, 157)
            val full = if (prefix.isNotEmpty() && name.isNotEmpty()) "$prefix/$name" else name
            val size = parseOctal(header, 124, 12)
            val typeFlag = header[156]
            val blocks = ((size + 511) / 512).toInt()
            val target = File(dst, full).canonicalFile
            if (full.isEmpty() || !inside(target.path, dstCanon)) {
                onSkip()
                skip(input, blocks.toLong() * 512L)
                continue
            }
            when (typeFlag) {
                '5'.code.toByte() -> target.mkdirs()
                '0'.code.toByte(), '7'.code.toByte(), 0.toByte() -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        var left = size
                        val buf = ByteArray(8192)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    count++
                }
                else -> onSkip()   // 符号链接之类，跳过
            }
            skip(input, blocks.toLong() * 512L - size)
        }
    }

    private fun parseString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8).trim('\u0000', ' ')
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long =
        parseString(b, off, len).trim().toLongOrNull(8) ?: 0L

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skip(input: java.io.InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val got = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (got <= 0) break
            left -= got
        }
    }

    /* ---------------- APK 元信息 ---------------- */

    suspend fun apkInfo(ctx: Context, env: AgentEnv, path: String): ToolResult =
        withContext(Dispatchers.IO) {
            val p = guardOne(ctx, env, path) ?: return@withContext ToolResult(false, outside(path))
            if (!File(p).isFile) return@withContext ToolResult(false, "那不是文件：" + p)
            ToolResult(true, apkInfoAt(ctx, p))
        }

    /** 解 APK：包名 / 版本 / minSdk-targetSdk / 权限清单 */
    private fun apkInfoAt(ctx: Context, p: String): String = runCatching {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(p, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageArchiveInfo(p, 0)
        } ?: return "APK：解析不出来（多半不是合法安装包）\n"
        val ai = info.applicationInfo
        val sb = StringBuilder()
        sb.append("APK 包名：").append(info.packageName).append('\n')
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
        sb.append("APK 版本：").append(info.versionName ?: "?").append("（code ").append(code).append("）\n")
        if (ai != null) {
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault("")
            sb.append("APK 应用名：").append(label.ifBlank { "（归档包不解密，装完才读得出）" }).append('\n')
            sb.append("APK minSdk：").append(ai.minSdkVersion)
                .append(" targetSdk：").append(ai.targetSdkVersion).append('\n')
            val perms = ai.requestedPermissions?.toList().orEmpty()
            sb.append("APK 权限 ").append(perms.size).append(" 项")
            if (perms.isNotEmpty()) {
                sb.append("：").append(perms.take(12).joinToString(", ") { it.substringAfterLast('.') })
                if (perms.size > 12) sb.append(" …")
            }
            sb.append('\n')
        }
        sb.toString()
    }.getOrElse { "APK：读失败 ${it.message}\n" }

    private fun human(b: Long): String = human(b.toDouble())

    private fun human(b: Double): String = when {
        b >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / 1024 / 1024 / 1024)
        b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024 / 1024)
        b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024)
        else -> String.format(Locale.US, "%.0f B", b)
    }
}
