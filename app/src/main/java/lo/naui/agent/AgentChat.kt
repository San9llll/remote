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

    /**
     * 一次最多带多少条历史。
     *
     * 太多了反而糟 —— 上下文一满，模型抓不住当前在聊什么。
     * 30 条大约是十来轮对话，够用了。
     */
    private const val MAX_HISTORY = 30

    suspend fun run(
        ctx: Context,
        /** 哪个会话 —— Store 里所有东西都按它分开放 */
        conversationId: String,
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
            // 0 / 负数 = 不限；但真无限会跑飞，所以压一个 500 的硬顶
            val configured = AgentStore.maxToolRounds
            val maxRounds = if (configured <= 0) 500 else configured
            val tools = AgentTools.toolsFor(env)
            // ---- 组历史 ----
            //
            // 照 astrbot 那套来的（`astrbot/core/agent/context/`）。三件事：
            //
            //   ① 空消息**直接跳过** —— 以前拿"（空）"占位，模型会把它当真的话来回
            //   ② 带图的消息 content 用数组 [ {text}, {image_url} ]
            //   ③ 组完之后过一遍 **AgentContext.truncateByHalving**，
            //      它会裁掉一半 + 修 tool 配对（这个是 OpenAI 规范硬要求，
            //      Gemini 尤其严格；我们以前完全没做，很可能就是
            //      "模型理解有问题"的原因之一）
            val raw = mutableListOf<JSONObject>()
            history.forEach { m ->
                if (m.text.isBlank() && m.images.isEmpty()) return@forEach

                if (m.role == "user" && m.images.isNotEmpty()) {
                    val parts = JSONArray()
                    if (m.text.isNotBlank()) {
                        parts.put(JSONObject().put("type", "text").put("text", m.text))
                    }
                    m.images.forEach { url ->
                        parts.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", url))
                        )
                    }
                    raw += JSONObject().put("role", "user").put("content", parts)
                } else {
                    raw += JSONObject().put("role", m.role).put("content", m.text)
                }
            }
            // 裁 + 修序列
            val fixed = AgentContext.truncateByHalving(raw)
            val messages = JSONArray()
            fixed.forEach { messages.put(it) }

            val log = mutableListOf<String>()
            val steps = mutableListOf<ToolStep>()

            /**
             * 同一个工具连着失败的次数。
             *
             * 为什么要盯它：实机反馈过"一次回答用了 200 多次思考" ——
             * 其中一大块是模型**卡在同一个动作上反复撞**（命令报错、它换个写法再试、还错…）。
             * astrbot 那边也有类似的"别让它钻牛角尖"的机制。
             *
             * 这里定：同一个工具**连着失败 4 次**就强制收手，把情况说给用户听。
             */
            var lastFailedTool = ""
            var consecutiveFail = 0
            val reasonAll = StringBuilder()
            var round = 0
            var cached = 0
            var input = 0
            var output = 0
            var spent = 0L

            while (round++ < maxRounds) {
                // 新一轮 —— 这回是在跟模型说话，没在跑工具，把那条收掉
                AgentTaskStore.updateRunningTool(conversationId, "", "")
                onProgress(if (round == 1) "在想…" else "第 " + round + " 轮…")

                // ---- 空输出重试 ----
                //
                // 照 astrbot 那套：`EMPTY_OUTPUT_RETRY_ATTEMPTS = 3`，
                // 退避 1~4 秒。
                //
                // 为什么要它：模型偶尔会返回一个**完全空**的响应
                //（既没有正文、也没有工具调用）。这多半是服务端抽了一下，
                // 重试一次通常就有了。不重试的话，用户会直接看到
                // "（模型没说话）" —— 以为是坏了。
                var reply: AgentApi.Reply? = null
                var attempt = 0
                while (attempt < 3) {
                    val r = AgentApi.stream(
                        system = system,
                        messages = messages,
                        tools = tools,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        onReasoning = { t ->
                            reasonAll.append(t)
                            onReasoning(t)
                        },
                        onDelta = { onDelta(it) },
                    )
                    // "空"的定义：没正文 **且** 没工具调用
                    if (r.text.isNotBlank() || r.toolCalls.isNotEmpty()) {
                        reply = r
                        break
                    }
                    attempt++
                    if (attempt < 3) {
                        onProgress("没收到内容，重试第 " + attempt + " 次…")
                        kotlinx.coroutines.delay(1000L * attempt)
                    } else {
                        // 三次都是空的 —— 那就照实说，但把话说明白点
                        reply = r
                    }
                }
                val finalReply = reply ?: return@runCatching AgentRun(
                    reply = "连着三次都没收到内容。可能是服务商那边抽了，或者 Key / 模型不对 —— 你到设置里检查一下。",
                    toolLog = log,
                    steps = steps,
                    thinkRounds = round - 1,
                    usage = AgentApi.Usage(cached, input, output, spent),
                    reasoning = reasonAll.toString(),
                )

                cached += finalReply.usage.cachedTokens
                input += finalReply.usage.inputTokens
                output += finalReply.usage.outputTokens
                spent += finalReply.usage.millis
                onRound(round)

                if (finalReply.toolCalls.isEmpty()) {
                    AgentTaskStore.updateRunningTool(conversationId, "", "")
                    return@runCatching AgentRun(
                        reply = finalReply.text.ifBlank { "（模型没说话）" },
                        toolLog = log,
                        steps = steps,
                        thinkRounds = round - 1,
                        usage = AgentApi.Usage(cached, input, output, spent),
                        reasoning = reasonAll.toString(),
                    )
                }

                messages.put(AgentApi.assistantToolMessage(finalReply.toolCalls))

                finalReply.toolCalls.forEach { call ->
                    val brief = briefArgs(call.args)
                    val label = friendlyName(call.name)
                    onProgress("正在用 " + label)

                    // 告诉界面"现在在跑什么"，好让进度条转起来
                    AgentTaskStore.updateRunningTool(conversationId, label, guessHint(call.name, call.args))

                    val t0 = System.currentTimeMillis()
                    var result = AgentTools.run(
                        ctx, env, call.name, call.args, askUser,
                        // 逐行看输出，认出下载进度和速度就更新到界面上
                        onLine = { line ->
                            Progress.parse(line)?.let { AgentTaskStore.updateToolProgress(conversationId, it) }
                            Progress.speed(line)?.let { AgentTaskStore.updateToolSpeed(conversationId, it) }
                        },
                    )

                    // ---- 要 root？弹窗问 ----
                    //
                    // 用户的要求：**勾了 root 也默认只用普通权限和沙箱**，
                    // 只有 Agent 说"这条必须 su"时才拦。
                    // 弹窗中间显示具体命令、下面显示它给的理由。
                    if (!result.ok && result.output.startsWith(NEED_ROOT_PREFIX)) {
                        val payload = result.output.removePrefix(NEED_ROOT_PREFIX)
                        val cmd = payload.substringBefore("|")
                        val why = payload.substringAfter("|", "")

                        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        AgentTaskStore.pendingConfirm = AgentTaskStore.ConfirmRequest(
                            conversationId = conversationId,
                            hit = lo.naui.agent.DangerGuard.Hit(
                                category = lo.naui.agent.DangerGuard.Category(
                                    id = "root",
                                    label = "需要 root",
                                    note = "它会拿到系统最高权限，能改任何东西",
                                    consequence = "跑错了可能让系统出问题、或者把你的数据弄没。",
                                    regexes = emptyList(),
                                ),
                                // ⚠️ Hit 的字段叫 matched，不是 target
                                matched = cmd,
                            ),
                            answer = gate,
                            command = cmd,
                            reason = why,
                            forRoot = true,
                        )
                        AgentTaskStore.updateRunningTool(
                            conversationId, "", "等你同意用 root"
                        )

                        val agreed = gate.await()
                        result = if (agreed) {
                            val out = runCatching {
                                lo.naui.sys.Privilege.exec(ctx, cmd)
                            }.getOrNull()
                            if (out != null) {
                                ToolResult(true, out)
                            } else {
                                ToolResult(false, "提权执行失败（su 没拿到？）")
                            }
                        } else {
                            ToolResult(false, "用户不同意用 root。换个不需要 root 的办法，或者跟他解释清楚为什么非要 root。")
                        }
                    }
                    val cost = System.currentTimeMillis() - t0
                    // ⚠️ 这里**故意不清**。
                    // 以前每跑完一个工具就清一次、下一个再设一次，
                    // 界面上那条"正在下载…"就一闪一闪（用户说的"一直闪"）。
                    // 让它留着，等下一轮开始或者整轮结束时统一收。

                    // ---- 连着失败就收手 ----
                    if (result.ok) {
                        consecutiveFail = 0
                        lastFailedTool = ""
                    } else {
                        if (call.name == lastFailedTool) {
                            consecutiveFail++
                        } else {
                            lastFailedTool = call.name
                            consecutiveFail = 1
                        }
                        if (consecutiveFail >= 4) {
                            // 别让它继续撞了
                            AgentTaskStore.updateRunningTool(conversationId, "", "")
                            return@runCatching AgentRun(
                                reply = "「" + label + "」连着 " + consecutiveFail +
                                    " 次都失败了，我先停下。\n\n最后一次的报错：\n" +
                                    result.output.take(600) +
                                    "\n\n换个思路，或者你告诉我该怎么做。",
                                toolLog = log,
                                steps = steps,
                                thinkRounds = round,
                                usage = AgentApi.Usage(cached, input, output, spent),
                                reasoning = reasonAll.toString(),
                            )
                        }
                    }

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
                    "想让它多跑的话，去聊天页那个 ⚙ 面板把「工具调用上限」设成不限。",
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
                AgentTools.READ_MANY -> "正在批量读文件"
                AgentTools.WRITE -> "正在写文件"
                AgentTools.LIST -> "正在看目录"
                AgentTools.DEVICE -> "正在问设备信息"
                AgentTools.FIND -> "正在找文件"
                AgentTools.GREP -> "正在搜内容"
                AgentTools.BATCH -> "正在批量执行"
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
        AgentTools.BATCH -> "批量执行"
        AgentTools.READ_MANY -> "批量读文件"
        AgentTools.FIND -> "找文件"
        AgentTools.GREP -> "搜内容"
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
