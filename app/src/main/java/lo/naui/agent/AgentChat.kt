package lo.naui.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一次工具调用干了什么（给界面展示，**不外露命令原文**） */
data class ToolStep(
    val name: String,
    /** 人话名字：执行命令 / 读文件 … */
    val label: String,
    val brief: String,
    val ok: Boolean,
    val outputChars: Int,
    /** 花在这件事上的时间 */
    val millis: Long,
)

/** 一轮跑完的结果 */
data class AgentRun(
    val reply: String,
    /** 中间调了哪些工具 */
    val toolLog: List<String> = emptyList(),
    /** 结构化的工具记录（带耗时，界面用） */
    val steps: List<ToolStep> = emptyList(),
    /** 这一轮里模型想了几次（就是发了几次请求） */
    val thinkRounds: Int = 0,
    /** 这一轮总共的用量（多轮请求累加） */
    val usage: AgentApi.Usage = AgentApi.Usage(),
    /** 思考内容（R1 / o1 这类才有） */
    val reasoning: String = "",
)

/**
 * 带工具的对话。
 *
 * 流程跟 astrbot 那边一样：把工具定义一起发过去，
 * 模型说"我要调 xxx" → 我们执行 → 结果塞回去 → 再问一遍，
 * 直到它不再调工具、只说话为止。
 *
 * **走流式**：正文和思考内容都是边生成边吐给界面的，
 * 不用等整段回来才显示。轮数上限由用户配（默认 8）。
 */
object AgentChat {

    suspend fun run(
        ctx: Context,
        system: String,
        history: List<ChatMessage>,
        env: AgentEnv,
        maxTokens: Int,
        temperature: Float,
        onProgress: (String) -> Unit,
        /** 思考内容的增量 */
        onReasoning: (String) -> Unit = {},
        /** 正文的增量 */
        onDelta: (String) -> Unit = {},
        /** 每转完一轮通知一声（界面拿它更新"思考了 N 次"） */
        onRound: (Int) -> Unit = {},
        /** 碰上危险动作时问用户（策略是"每次问"才会调） */
        askUser: (suspend (DangerGuard.Hit) -> Boolean)? = null,
    ): Result<AgentRun> = withContext(Dispatchers.IO) {
        runCatching {
            val maxRounds = AgentStore.maxToolRounds.coerceIn(1, 64)
            val tools = AgentTools.toolsFor(env)
            val messages = JSONArray()
            history.forEach { m ->
                messages.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", if (m.text.isBlank()) "（空）" else m.text)
                )
            }

            val log = mutableListOf<String>()
            val steps = mutableListOf<ToolStep>()
            val reasonAll = StringBuilder()
            var round = 0
            var cached = 0
            var input = 0
            var output = 0
            var spent = 0L

            while (round++ < maxRounds) {
                onProgress(if (round == 1) "在想…" else "第 " + round + " 轮…")

                val reply = AgentApi.stream(
                    system = system,
                    messages = messages,
                    tools = tools,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    onReasoning = { r ->
                        reasonAll.append(r)
                        onReasoning(r)
                    },
                    onDelta = { onDelta(it) },
                )

                cached += reply.usage.cachedTokens
                input += reply.usage.inputTokens
                output += reply.usage.outputTokens
                spent += reply.usage.millis
                onRound(round)

                if (reply.toolCalls.isEmpty()) {
                    return@runCatching AgentRun(
                        reply = reply.text.ifBlank { "（模型没说话）" },
                        toolLog = log,
                        steps = steps,
                        thinkRounds = round - 1,
                        usage = AgentApi.Usage(cached, input, output, spent),
                        reasoning = reasonAll.toString(),
                    )
                }

                messages.put(AgentApi.assistantToolMessage(reply.toolCalls))

                reply.toolCalls.forEach { call ->
                    val brief = briefArgs(call.args)
                    val label = friendlyName(call.name)
                    onProgress("正在用 " + label)

                    // 告诉界面"现在在跑什么"，好让进度条转起来
                    AgentTaskStore.setRunningTool(label, guessHint(call.name, call.args))

                    val t0 = System.currentTimeMillis()
                    val result = AgentTools.run(ctx, env, call.name, call.args, askUser)
                    val cost = System.currentTimeMillis() - t0
                    AgentTaskStore.setRunningTool("", "")

                    // 只记"用了什么、成没成、多大动静"，命令原文不进这条
                    val line = "▸ " + label + " · " + brief +
                        "\n    " + (if (result.ok) "完成" else "失败") +
                        " · 返回 " + result.output.length + " 字 · " + cost + "ms" +
                        if (result.ok) "" else "：" + result.output.take(200)
                    log += line
                    steps += ToolStep(
                        name = call.name,
                        label = label,
                        brief = brief,
                        ok = result.ok,
                        outputChars = result.output.length,
                        millis = cost,
                    )

                    messages.put(
                        AgentApi.toolMessage(
                            call.id,
                            call.name,
                            if (result.ok) result.output else "出错了：" + result.output,
                        )
                    )
                }
            }

            AgentRun(
                reply = "（工具已经调了 " + maxRounds + " 轮还没完，先停一下）\n" +
                    "想让它多跑几轮的话，去聊天页那个 ⚙ 面板里把上限调高。",
                toolLog = log,
                steps = steps,
                thinkRounds = round,
                usage = AgentApi.Usage(cached, input, output, spent),
                reasoning = reasonAll.toString(),
            )
        }
    }

    /**
     * 猜这条工具大概在干嘛。
     *
     * 重点是把**下载**认出来 —— 用户在 Agent 里让它下东西的时候，
     * 界面上那条进度条得说"正在下载"，而不是干巴巴的"执行命令"。
     */
    private fun guessHint(toolName: String, args: JSONObject): String {
        if (toolName != AgentTools.SHELL) {
            return when (toolName) {
                AgentTools.READ -> "正在读文件"
                AgentTools.WRITE -> "正在写文件"
                AgentTools.LIST -> "正在看目录"
                AgentTools.DEVICE -> "正在问设备信息"
                else -> "正在干活"
            }
        }
        val cmd = args.optString("command", "").lowercase()
        return when {
            cmd.contains("curl") || cmd.contains("wget") -> "正在下载…"
            cmd.contains("git clone") || cmd.contains("git pull") -> "正在拉代码…"
            cmd.contains("pkg install") || cmd.contains("apt install") ||
                cmd.contains("apt-get install") -> "正在装东西…"
            cmd.contains("tar ") || cmd.contains("unzip") || cmd.contains("7z ") -> "正在解压…"
            cmd.contains("cp ") || cmd.contains("mv ") || cmd.contains("rsync") -> "正在拷文件…"
            cmd.contains("find ") || cmd.contains("grep ") -> "正在找东西…"
            else -> "正在执行命令"
        }
    }

    /** 工具的内部名字 → 人话 */
    private fun friendlyName(name: String): String = when (name) {
        AgentTools.SHELL -> "执行命令"
        AgentTools.READ -> "读文件"
        AgentTools.WRITE -> "写文件"
        AgentTools.LIST -> "看目录"
        AgentTools.DEVICE -> "看设备信息"
        else -> name
    }

    /** 参数摘要，给界面显示一行 */
    private fun briefArgs(args: JSONObject): String {
        val s = args.toString()
        return if (s.length <= 120) s else s.take(120) + "…"
    }
}
