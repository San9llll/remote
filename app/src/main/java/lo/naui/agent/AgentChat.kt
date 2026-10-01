package lo.naui.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一轮跑完的结果 */
data class AgentRun(
    val reply: String,
    /** 中间调了哪些工具、结果咋样（给界面展示用） */
    val toolLog: List<String>,
)

/**
 * 带工具的对话。
 *
 * 流程跟 astrbot 那边一样：把工具定义一起发过去，
 * 模型说"我要调 xxx" → 我们执行 → 结果塞回去 → 再问一遍，
 * 直到它不再调工具、只说话为止。
 *
 * 最多转 8 圈，防止它自己绕死。
 */
object AgentChat {

    private const val MAX_ROUNDS = 8

    suspend fun run(
        ctx: Context,
        system: String,
        history: List<ChatMessage>,
        env: AgentEnv,
        maxTokens: Int,
        temperature: Float,
        onProgress: (String) -> Unit,
        /** 碰上危险动作时问用户（策略是"每次问"才会调） */
        askUser: (suspend (String) -> Boolean)? = null,
    ): Result<AgentRun> = withContext(Dispatchers.IO) {
        runCatching {
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
            var round = 0

            while (round++ < MAX_ROUNDS) {
                onProgress(if (round == 1) "在想…" else "第 " + round + " 轮…")
                val reply = AgentApi.raw(system, messages, tools, temperature, maxTokens)

                if (reply.toolCalls.isEmpty()) {
                    val text = reply.text.ifBlank { "（模型没说话）" }
                    return@runCatching AgentRun(text, log)
                }

                // 把它"要调工具"那条原样记进去，否则下一轮上下文对不上
                messages.put(AgentApi.assistantToolMessage(reply.toolCalls))

                reply.toolCalls.forEach { call ->
                    val brief = briefArgs(call.args)
                    onProgress("正在用 " + call.name + " " + brief)
                    val result = AgentTools.run(ctx, env, call.name, call.args, askUser)
                    log += "▸ " + call.name + " " + brief + "\n" + result.output.take(1500)
                    messages.put(
                        AgentApi.toolMessage(
                            call.id,
                            call.name,
                            if (result.ok) result.output else "出错了：" + result.output,
                        )
                    )
                }
            }

            AgentRun("（工具调了 " + MAX_ROUNDS + " 轮还没完，先停一下）", log)
        }
    }

    /** 参数摘要，给界面显示一行 */
    private fun briefArgs(args: JSONObject): String {
        val s = args.toString()
        return if (s.length <= 120) s else s.take(120) + "…"
    }
}
