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
    const val READ = "read_file"
    const val WRITE = "write_file"
    const val LIST = "list_dir"
    const val DEVICE = "device_info"

    val ALL: List<AgentTool> = listOf(
        AgentTool(
            name = SHELL,
            description = "在设备上执行一条 shell 命令，返回它的输出。" +
                "可以看文件、跑命令、装东西、改配置。沙箱环境下只能在 app 的 data 目录里活动。",
            properties = JSONObject().put("command", str("要执行的命令，比如 ls -al /sdcard 或者 pm list packages")),
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
     * [askUser] 由界面给：收一个"为什么危险"的描述，回一个同意/不同意。
     * 策略是"每次问"才会调它。
     */
    suspend fun run(
        ctx: Context,
        env: AgentEnv,
        name: String,
        args: JSONObject,
        askUser: (suspend (String) -> Boolean)? = null,
    ): ToolResult {
        // 先过危险闸门
        DangerGuard.risk(name, args)?.let { reason ->
            when (AgentStore.dangerPolicy) {
                DangerGuard.Policy.Deny -> return ToolResult(
                    false,
                    "这个动作被安全策略挡下来了（" + reason + "）。" +
                        "如果确实要做，让用户在 Agent 配置里把「危险操作」改成每次都问或者放行。",
                )
                DangerGuard.Policy.Allow -> Unit
                DangerGuard.Policy.Ask -> {
                    val ok = askUser?.invoke(reason) ?: true
                    if (!ok) {
                        return ToolResult(false, "用户拒绝了这次操作（" + reason + "），换个办法或者先问清楚。")
                    }
                }
            }
        }
        return runUnchecked(ctx, env, name, args)
    }

    private suspend fun runUnchecked(
        ctx: Context,
        env: AgentEnv,
        name: String,
        args: JSONObject,
    ): ToolResult = when (name) {
        SHELL -> AgentRunner.shell(ctx, env, args.optString("command", "").trim())

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
