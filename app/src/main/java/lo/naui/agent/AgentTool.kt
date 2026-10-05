package lo.naui.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一个能交给 AI 调的工具 */
data class AgentTool(
    val name: String,
    val description: String,
    /** JSON Schema 的 properties */
    val properties: JSONObject,
    val required: List<String>,
) {
    /** OpenAI function calling 的写法 */
    fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required))
                )
        )
}

private fun str(desc: String): JSONObject =
    JSONObject().put("type", "string").put("description", desc)

private fun int(desc: String): JSONObject =
    JSONObject().put("type", "integer").put("description", desc)

/**
 * 内嵌工具链。
 *
 * 给 AI 这几个"手"：跑命令、读写文件、列目录。
 * 跑在哪个环境由用户在输入栏那个弹窗里选（本机 root / 沙箱）。
 *
 * 工具执行前**不需要用户确认** —— 用户明确要"让 ai 真正的能够操作手机底层"。
 * 但沙箱模式有路径围栏，本机模式才放得开。
 */
object AgentTools {

    const val SHELL = "run_shell"
    const val BATCH = "batch_shell"
    const val READ_MANY = "read_many"
    const val FIND = "find_files"
    const val GREP = "grep_text"
    const val READ = "read_file"
    const val WRITE = "write_file"
    const val LIST = "list_dir"
    const val DEVICE = "device_info"

    val ALL: List<AgentTool> = listOf(
        AgentTool(
            name = SHELL,
            description = "在设备上执行一条 shell 命令，返回它的输出。" +
                "可以看文件、跑命令、装东西、改配置。沙箱环境下只能在 app 的 data 目录里活动。\n\n" +
                "**注意**：默认都是普通用户权限。如果这条命令**必须**用 root（要写系统分区、" +
                "动别的 app 的数据、改系统设置），那要先把 `reason` 填上 —— " +
                "用户会看到你想干什么、以及你为什么要这么干，他同意了你才能拿到 su。",
            properties = JSONObject()
                .put("command", str("要执行的命令，比如 ls -al /sdcard 或者 pm list packages"))
                .put("reason", str(
                    "如果这条命令需要 root 权限，在这儿说清楚**为什么**。" +
                        "比如「要改 /system 下的某个配置来关掉这个功能，因为设置界面里没有开关」。" +
                        "不需要 root 的命令就不用填。"
                ))
                .put("need_root", JSONObject()
                    .put("type", "boolean")
                    .put("description", "这条命令是不是必须用 root 跑，默认 false")),
            required = listOf("command"),
        ),
        AgentTool(
            name = READ,
            description = "读一个文本文件的内容",
            properties = JSONObject()
                .put("path", str("文件路径"))
                .put("max_bytes", int("最多读多少字节，默认 200000")),
            required = listOf("path"),
        ),
        AgentTool(
            name = WRITE,
            description = "把内容写进一个文件（会覆盖），父目录不存在会自动建",
            properties = JSONObject()
                .put("path", str("文件路径"))
                .put("content", str("要写入的文本内容")),
            required = listOf("path", "content"),
        ),
        AgentTool(
            name = LIST,
            description = "列出一个目录里有什么",
            properties = JSONObject().put("path", str("目录路径")),
            required = listOf("path"),
        ),
        AgentTool(
            name = BATCH,
            description = "一次跑多条命令（串行执行，按顺序）。" +
                "**需要连着做几步的时候优先用它** —— 比如 `ls` 看完再 `cat` 再 `grep`，" +
                "分包发的话每一条都要跟模型来回一次，很慢。一次提交能省很多轮。",
            properties = JSONObject()
                .put("commands", JSONObject()
                    .put("type", "array")
                    .put("description", "要依次执行的命令列表")
                    .put("items", JSONObject().put("type", "string")))
                .put("stop_on_error", JSONObject()
                    .put("type", "boolean")
                    .put("description", "遇到失败就停，默认 false 继续跑")),
            required = listOf("commands"),
        ),
        AgentTool(
            name = READ_MANY,
            description = "一次读多个文件的内容。想知道好几个文件里都是什么时用它，" +
                "比一个个 read_file 快得多。最多 20 个。",
            properties = JSONObject()
                .put("paths", JSONObject()
                    .put("type", "array")
                    .put("description", "要读的文件路径列表")
                    .put("items", JSONObject().put("type", "string"))),
            required = listOf("paths"),
        ),
        AgentTool(
            name = FIND,
            description = "按**文件名**找文件。比如找所有 .log、所有 build.gradle.kts。" +
                "不知道东西在哪的时候先用它，别一层层 ls 翻。",
            properties = JSONObject()
                .put("root", str("从哪儿开始找，默认当前目录"))
                .put("name", str("文件名匹配，支持通配符，比如 *.kt、config.*"))
                .put("max_depth", int("最多往下找几层，默认 5")),
            required = listOf("name"),
        ),
        AgentTool(
            name = GREP,
            description = "在**文件内容**里搜关键词，返回匹配的行和行号。" +
                "找「这东西在哪定义、在哪用到」就靠它，比读一堆文件快。",
            properties = JSONObject()
                .put("root", str("从哪个目录找，默认当前目录"))
                .put("pattern", str("要找的内容（普通字符串，不是正则）"))
                .put("file_glob", str("只搜哪些文件，默认 * ，比如 *.kt")),
            required = listOf("pattern"),
        ),
        AgentTool(
            name = DEVICE,
            description = "问这台设备的基本情况：型号、安卓版本、当前权限、屏幕、电量",
            properties = JSONObject(),
            required = emptyList(),
        ),
    )

    /** 这个环境允许用哪些工具（沙箱少一点） */
    fun toolsFor(env: AgentEnv): List<AgentTool> = when (env) {
        AgentEnv.Sandbox -> ALL.filter { it.name != DEVICE }
        AgentEnv.Host -> ALL
    }

    /* ================= 执行 ================= */

    /**
     * 要不要先问用户。
     *
     * [askUser] 由界面给：把命中的那一类递过去，回一个同意/不同意。
     * 策略是"每次问"才会调它。
     */
    suspend fun run(
        ctx: Context,
        env: AgentEnv,
        name: String,
        args: JSONObject,
        askUser: (suspend (DangerGuard.Hit) -> Boolean)? = null,
        /** 命令的实时输出行（下载进度靠它） */
        onLine: ((String) -> Unit)? = null,
    ): ToolResult {
        // 先过危险闸门
        DangerGuard.risk(name, args)?.let { hit ->
            when (AgentStore.dangerPolicy) {
                DangerGuard.Policy.Deny -> return ToolResult(
                    false,
                    "这个动作被安全策略挡下来了（" + hit.category.label + "）。" +
                        "如果确实要做，让用户在 Agent 配置里把「危险操作」改成每次都问或者放行。",
                )
                DangerGuard.Policy.Allow -> Unit
                DangerGuard.Policy.Ask -> {
                    val ok = askUser?.invoke(hit) ?: true
                    if (!ok) {
                        return ToolResult(
                            false,
                            "用户拒绝了这次操作（" + hit.category.label + "：" + hit.category.note +
                                "），换个办法或者先问清楚。",
                        )
                    }
                }
            }
        }
        return runUnchecked(ctx, env, name, args, onLine)
    }

    private suspend fun runUnchecked(
        ctx: Context,
        env: AgentEnv,
        name: String,
        args: JSONObject,
        onLine: ((String) -> Unit)? = null,
    ): ToolResult = when (name) {
        SHELL -> AgentRunner.shell(
            ctx, env,
            args.optString("command", "").trim(),
            onLine,
            // 要 root 的话把理由一起带上 —— 界面弹窗要用
            needRoot = args.optBoolean("need_root", false),
            reason = args.optString("reason", "").trim(),
        )

        BATCH -> {
            val arr = args.optJSONArray("commands")
            val list = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { list += it }
            AgentRunner.batchShell(ctx, env, list, args.optBoolean("stop_on_error", false))
        }

        READ_MANY -> {
            val arr = args.optJSONArray("paths")
            val list = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { list += it }
            AgentRunner.readMany(ctx, env, list)
        }

        FIND -> AgentRunner.findFiles(
            ctx, env,
            args.optString("root", ".").trim(),
            args.optString("name", "*").trim(),
            args.optInt("max_depth", 5),
        )

        GREP -> AgentRunner.grepText(
            ctx, env,
            args.optString("root", ".").trim(),
            args.optString("pattern", "").trim(),
            args.optString("file_glob", "*").trim(),
        )

        READ -> AgentRunner.readFile(
            ctx, env,
            args.optString("path", "").trim(),
            args.optInt("max_bytes", 200_000).coerceIn(1_000, 800_000),
        )

        WRITE -> AgentRunner.writeFile(
            ctx, env,
            args.optString("path", "").trim(),
            args.optString("content", ""),
        )

        LIST -> AgentRunner.listDir(ctx, env, args.optString("path", ".").trim())

        DEVICE -> deviceInfo(ctx, env)

        else -> ToolResult(false, "没有这个工具：" + name)
    }

    private suspend fun deviceInfo(ctx: Context, env: AgentEnv): ToolResult {
        val level = lo.naui.sys.Privilege.level(ctx)
        val sb = StringBuilder()
        sb.append("型号：").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append('\n')
        sb.append("安卓：").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append("架构：").append(android.os.Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        sb.append("当前权限：").append(level.label).append('\n')
        sb.append("执行环境：").append(env.label).append('\n')
        sb.append("Termux 环境：")
            .append(if (lo.naui.term.Bootstrap.isInstalled(ctx)) "装了" else "没装").append('\n')
        sb.append("沙箱根目录：").append(AgentRunner.sandboxRoot(ctx).absolutePath).append('\n')
        sb.append("可用空间：")
            .append(ctx.filesDir.usableSpace / 1024 / 1024).append(" MB")
        return ToolResult(true, sb.toString())
    }
}
