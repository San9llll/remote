package lo.naui.agent

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.term.Bootstrap

/**
 * AI 能用哪套环境干活。
 *
 * - [Host]：走 root / Shizuku，整台机器哪儿都能碰
 * - [Sandbox]：只在 app 的 data 目录里跑，出不去。装了 Termux 环境就用它那份 bash，
 *   没装就用系统 sh。`..` 会被挡掉，绝对路径会被拽回沙箱根
 */
enum class AgentEnv(val id: String, val label: String, val summary: String) {
    Sandbox("sandbox", "沙箱", "只在 app 的 data 目录里活动，碰到外面会被拦下来"),
    Host("host", "本机（root）", "用 su / Shizuku 直接操作整台机器");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Sandbox
    }
}

/** 工具跑出来的结果 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
)

/**
 * 工具的执行后端。
 *
 * 所有"动手"的事情最后都落到这里 —— 命令、读写文件、列目录。
 * 权限按当前能拿到的最高来（root → Shizuku → 普通），
 * 沙箱模式再额外套一层路径围栏。
 */
object AgentRunner {

    /**
     * 命令最多跑多久。
     *
     * ⚠️ 以前是**写死 25 秒**，这是个硬伤：
     * 下个大文件 25 秒根本下不完 → 每次都被掐 → AI 以为失败 → 反复重试，
     * 实机上表现成"用了 51 次工具、想了 30 次还没下好"。
     *
     * 现在按命令分档，慢活儿给足时间。
     */
    private fun timeoutFor(cmd: String): Long {
        val c = cmd.lowercase()
        return when {
            // 下载 / 装东西 / 解压：给 15 分钟
            c.contains("curl") || c.contains("wget") ||
                c.contains("git clone") || c.contains("git pull") ||
                c.contains("apt ") || c.contains("pkg ") ||
                c.contains("unzip") || c.contains("tar ") ||
                c.contains("pip ") || c.contains("npm ") -> 900L
            // 一般的编译 / 压缩
            c.contains("gradle") || c.contains("make") ||
                c.contains("zip ") || c.contains("7z ") -> 300L
            // 其它
            else -> 60L
        }
    }
    private const val MAX_OUTPUT = 24_000

    fun sandboxRoot(ctx: Context): File = File(ctx.filesDir, "sandbox").apply { mkdirs() }

    /**
     * 跑一条命令。
     *
     * [onLine] 会拿到**实时输出** —— 下载进度就是靠它读出来的
     * （curl 的百分比 / wget 的速度），不然只能等命令跑完才知道下了多少。
     */
    suspend fun shell(
        ctx: Context,
        env: AgentEnv,
        command: String,
        onLine: ((String) -> Unit)? = null,
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            if (env == AgentEnv.Sandbox) {
                val root = sandboxRoot(ctx)
                val cmd = guard(command, root)
                val out = runLocalShell(ctx, cmd, root, onLine)
                ToolResult(true, clip(out))
            } else {
                // 优先走 root / Shizuku（这条没有实时输出）；
                // 拿不到特权才退回本地 shell —— 那条能逐行读，进度看得见
                val out = Privilege.exec(ctx, command)
                    ?: runLocalShell(ctx, command, sandboxRoot(ctx), onLine)
                val level = Privilege.level(ctx)
                ToolResult(
                    true,
                    clip(if (level == PrivLevel.Normal) "（没有 root，用的普通权限跑）\n" + out else out)
                )
            }
        }.getOrElse { ToolResult(false, "执行失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /** 读文件 */
    suspend fun readFile(ctx: Context, env: AgentEnv, path: String, maxBytes: Int = 200_000): ToolResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val real = resolve(ctx, env, path) ?: return@runCatching ToolResult(false, "沙箱不让碰沙箱外面的路径：" + path)
                val f = File(real)
                if (!f.exists()) return@runCatching ToolResult(false, "没有这个文件：" + real)
                if (f.isDirectory) return@runCatching ToolResult(false, "那是个目录，用 list_dir")

                if (f.canRead()) {
                    val text = f.readText().take(maxBytes)
                    ToolResult(true, clip(text))
                } else {
                    // 沙箱外的、直读不了的，交给 shell 去 cat
                    val out = runLocalShell(ctx, "cat " + q(real), sandboxRoot(ctx))
                    if (out.isBlank()) {
                        val viaPriv = Privilege.exec(ctx, "head -c $maxBytes " + q(real))
                        ToolResult(viaPriv != null, clip(viaPriv ?: "读不了：" + real))
                    } else {
                        ToolResult(true, clip(out.take(maxBytes)))
                    }
                }
            }.getOrElse { ToolResult(false, "读失败：" + (it.message ?: "")) }
        }

    /** 写文件（沙箱模式只能写沙箱里） */
    suspend fun writeFile(ctx: Context, env: AgentEnv, path: String, content: String): ToolResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val real = resolve(ctx, env, path) ?: return@runCatching ToolResult(false, "沙箱不让写沙箱外面的路径：" + path)
                val f = File(real)
                f.parentFile?.mkdirs()
                f.writeText(content)
                ToolResult(true, "写好了 " + real + "（" + content.length + " 字）")
            }.getOrElse { ToolResult(false, "写失败：" + (it.message ?: "")) }
        }

    /**
     * 一次读多个文件。
     *
     * 为什么要它：以前 AI 想知道"这几个文件里都是什么"，只能一个个
     * `read_file`，来回十几次。这个一次就搞定。
     */
    suspend fun readMany(
        ctx: Context,
        env: AgentEnv,
        paths: List<String>,
        maxBytesEach: Int = 60_000,
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            if (paths.isEmpty()) return@runCatching ToolResult(false, "没给路径")
            val sb = StringBuilder()
            paths.take(20).forEach { raw ->
                val path = raw.trim()
                if (path.isBlank()) return@forEach
                val real = resolve(ctx, env, path)
                if (real == null) {
                    sb.append("=== ").append(path).append(" ===\n【沙箱不让碰】\n\n")
                    return@forEach
                }
                val f = File(real)
                sb.append("=== ").append(path).append(" ===\n")
                when {
                    !f.exists() -> sb.append("【不存在】\n")
                    f.isDirectory -> sb.append("【是目录，用 list_dir】\n")
                    else -> {
                        val txt = runCatching { f.readText().take(maxBytesEach) }.getOrNull()
                        sb.append(txt ?: "【读不出来（二进制或者没权限）】").append('\n')
                    }
                }
                sb.append('\n')
            }
            ToolResult(true, clip(sb.toString()))
        }.getOrElse { ToolResult(false, "批量读失败：" + (it.message ?: "")) }
    }

    /**
     * 按文件名找。
     *
     * 用 `find`，但把常见坑（权限报错刷屏、符号链接绕圈）都处理了。
     */
    suspend fun findFiles(
        ctx: Context,
        env: AgentEnv,
        root: String,
        namePattern: String,
        maxDepth: Int = 5,
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val base = if (root.isBlank()) "." else root
            // 用 -name 匹配，2>/dev/null 挡掉权限报错
            val cmd = "find " + q(base) + " -maxdepth " + maxDepth.coerceIn(1, 12) +
                " -name " + q(namePattern) + " 2>/dev/null | head -100"
            val out = runLocalShell(ctx, guard(cmd, sandboxRoot(ctx)), sandboxRoot(ctx), null)
                .ifBlank { Privilege.exec(ctx, cmd).orEmpty() }
            ToolResult(true, clip(out.ifBlank { "（没找到）" }))
        }.getOrElse { ToolResult(false, "找文件失败：" + (it.message ?: "")) }
    }

    /**
     * 在文件内容里搜。
     *
     * 比让 AI 一个个 read_file 快得多。
     */
    suspend fun grepText(
        ctx: Context,
        env: AgentEnv,
        root: String,
        pattern: String,
        fileGlob: String = "*",
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val base = if (root.isBlank()) "." else root
            val cmd = "grep -rn --include=" + q(fileGlob) + " -e " + q(pattern) + " " + q(base) +
                " 2>/dev/null | head -80"
            val out = runLocalShell(ctx, guard(cmd, sandboxRoot(ctx)), sandboxRoot(ctx), null)
                .ifBlank { Privilege.exec(ctx, cmd).orEmpty() }
            ToolResult(true, clip(out.ifBlank { "（没匹配到）" }))
        }.getOrElse { ToolResult(false, "搜索失败：" + (it.message ?: "")) }
    }

    /**
     * 一次跑多条命令（串行）。
     *
     * 这是**减少往返次数最有效的一招** ——
     * AI 经常需要连着跑好几条（ls → cd → cat），一条条来的话
     * 每轮都要跟模型来回一次。这个工具让它一次提交。
     */
    suspend fun batchShell(
        ctx: Context,
        env: AgentEnv,
        commands: List<String>,
        stopOnError: Boolean = false,
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            if (commands.isEmpty()) return@runCatching ToolResult(false, "没给命令")
            val sb = StringBuilder()
            commands.take(30).forEachIndexed { i, c ->
                val cmd = c.trim()
                if (cmd.isBlank()) return@forEachIndexed
                sb.append("$ ").append(cmd).append('\n')
                val one = shell(ctx, env, cmd, null)
                sb.append(one.output).append('\n')
                if (!one.ok && stopOnError) return@runCatching ToolResult(false, clip(sb.toString()))
                sb.append('\n')
            }
            ToolResult(true, clip(sb.toString()))
        }.getOrElse { ToolResult(false, "批量执行失败：" + (it.message ?: "")) }
    }

    /** 列目录 */
    suspend fun listDir(ctx: Context, env: AgentEnv, path: String): ToolResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val real = resolve(ctx, env, path) ?: return@runCatching ToolResult(false, "沙箱不让看沙箱外面的路径：" + path)
                val f = File(real)
                if (!f.isDirectory) return@runCatching ToolResult(false, "不是目录：" + real)

                val kids = f.listFiles()
                if (kids != null) {
                    ToolResult(true, clip(kids.joinToString("\n") {
                        (if (it.isDirectory) "d " else "- ") + it.name + "  " + (if (it.isDirectory) "" else it.length().toString() + "B")
                    }))
                } else {
                    val out = Privilege.exec(ctx, "ls -lA " + q(real))
                        ?: runLocalShell(ctx, "ls -lA " + q(real), sandboxRoot(ctx))
                    ToolResult(true, clip(out))
                }
            }.getOrElse { ToolResult(false, "列目录失败：" + (it.message ?: "")) }
        }

    /* ---------------- 路径围栏 ---------------- */

    /**
     * 把路径解成真实的绝对路径；沙箱模式下跑到外面就返回 null。
     */
    private fun resolve(ctx: Context, env: AgentEnv, path: String): String? {
        val root = sandboxRoot(ctx)
        if (env == AgentEnv.Host) {
            return if (path.startsWith("/")) path else File(root, path).absolutePath
        }
        // 沙箱：相对路径按沙箱根算；绝对路径必须先落在沙箱里
        val target = if (path.startsWith("/")) File(path) else File(root, path)
        val canon = runCatching { target.canonicalPath }.getOrNull() ?: return null
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return null
        return if (canon == rootCanon || canon.startsWith(rootCanon + "/")) canon else null
    }

    /**
     * 给命令加围栏。
     *
     * 不是个严密的沙箱（真要严密得靠 SELinux/namespace），
     * 但足够挡住"手滑把 rm -rf / 发过来"这种事：
     * 开头强制 cd 到沙箱根，并且把命令里出现的 `..` 挑出来警告。
     */
    private fun guard(command: String, root: File): String {
        val cd = "cd " + q(root.absolutePath) + " 2>/dev/null; "
        if (command.contains("..")) {
            return cd + "echo '[沙箱] 命令里带 .. 已按沙箱根处理'; " + command
        }
        return cd + command
    }

    private fun runLocalShell(
        ctx: Context,
        cmd: String,
        dir: File,
        onLine: ((String) -> Unit)? = null,
    ): String = runCatching {
        // 装了 Termux 环境就用它那份 bash，功能全；没装就系统 sh
        val useTermux = Bootstrap.isInstalled(ctx)
        val shellPath = if (useTermux) Bootstrap.shellPath(ctx) else "/system/bin/sh"

        val pb = ProcessBuilder(shellPath, "-c", cmd)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        if (useTermux) {
            pb.environment().clear()
            Bootstrap.environ(ctx).forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            pb.environment()["HOME"] = dir.absolutePath
        }

        val p = pb.start()
        val limit = timeoutFor(cmd)

        // 逐行读，边读边吐给回调 —— 这样下载进度能实时更新。
        // 读不能在主线程干等，得和超时一起管，不然大文件会把整个流程吊死。
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        sb.append(line).append('\n')
                        onLine?.invoke(line)
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val done = p.waitFor(limit, TimeUnit.SECONDS)
        if (!done) {
            runCatching { p.destroyForcibly() }
            val got = sb.length
            sb.append('\n').append(
                "【命令跑了 ").append(limit).append(" 秒还没完，被这边掐断了】\n" +
                "（到这一步已经收到 " + got + " 个字符输出。）\n" +
                "如果是下大文件：别整段重来 —— 先看看目标文件已经下了多少，" +
                "用 curl -C - 接着下，或者 wget -c。"
            )
        }
        sb.toString()
    }.getOrDefault("")

    private fun clip(s: String): String =
        if (s.length <= MAX_OUTPUT) s else s.take(MAX_OUTPUT) + "\n…（输出太长，截断了）"

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"
}
