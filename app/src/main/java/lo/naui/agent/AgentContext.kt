package lo.naui.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 上下文管理。
 *
 * ## 这套是照抄 astrbot 的
 *
 * 用户要求"和 astrbot 一样的发送逻辑结构"，所以专门去翻了它的源码
 * （`astrbot/core/agent/context/`），下面三件是核心：
 *
 * ### ① `fixMessages` —— 保证 tool 配对
 *
 * 这是 **OpenAI Chat Completions 规范**要求的（Gemini 尤其严格）：
 *   · 每条 `tool` 消息**前面必须有**带 `tool_calls` 的 `assistant`
 *   · 每条 `assistant(tool_calls)` **后面必须跟**对应的 `tool` 响应
 *   · **孤立的 tool 消息直接丢掉**
 *
 * astrbot 的注释原文：
 *   > This is a requirement of the OpenAI Chat Completions API specification
 *   > (Gemini enforces this strictly).
 *
 * 我们以前**完全没做这个** —— 历史里只要有一条 tool 落单，
 * 整段对话就可能被模型理解错（"模型理解有问题"的一个很可能的原因）。
 *
 * ### ② `truncateByHalving` —— 超了减半
 *
 * 不是从头砍，而是**把非 system 的消息砍掉一半，留下最近的**，
 * 然后**从最近的第一条 user 开始**（保证序列合法），最后再过一遍 `fixMessages`。
 *
 * ### ③ 工具结果限长
 *
 * astrbot 那边是：
 *   · `TOOL_RESULT_MAX_ESTIMATED_TOKENS = 27_500`
 *   · `TOOL_RESULT_PREVIEW_MAX_ESTIMATED_TOKENS = 7_000`
 *
 * 中文里 1 token ≈ 1.5 字符（粗估），所以 27500 token ≈ 4 万字符。
 * 超过就只留头尾，中间用省略号接上 —— 头是命令，尾是报错，中间通常没用。
 */
object AgentContext {

    /** 工具结果最多留多少字符（≈27500 token） */
    const val TOOL_RESULT_MAX_CHARS = 40_000

    /** 超长时预览留多少（≈7000 token） */
    private const val TOOL_RESULT_PREVIEW_CHARS = 10_000

    /** 上下文用到这个比例就压（astrbot 是 0.82） */
    const val COMPRESS_THRESHOLD = 0.82

    /**
     * 历史最多带多少条。
     *
     * astrbot 是按 token 算的（要调 tokenizer），我们简化成条数。
     * 30 条约等于十来轮对话，配合下面的减半够用了。
     */
    const val MAX_HISTORY = 30

    /* ================= ① 修消息序列 ================= */

    /**
     * 修消息序列，保证 tool 配对合法。
     *
     * 规则（照 astrbot 的 `ContextTruncator.fix_messages`）：
     *
     * ```
     * 遇到 tool 消息：
     *   前面有挂着的 assistant(tool_calls)  → 收进这一组
     *   没有                                → 丢掉（孤立的）
     *
     * 遇到 assistant(tool_calls)：
     *   先把上一组收尾，再挂起自己
     *
     * 遇到普通消息：
     *   先把挂起的那组收尾，再正常放进去
     *
     * 收尾时：assistant 和它的 tool **要成对才收**，
     *        只有 assistant 没有 tool 的话整组丢掉
     * ```
     *
     * [messages] 是已经拼好的 JSON 数组（每项有 role / content / tool_calls）。
     */
    fun fixMessages(messages: List<JSONObject>): List<JSONObject> {
        if (messages.isEmpty()) return messages

        val fixed = mutableListOf<JSONObject>()
        var pendingAssistant: JSONObject? = null
        var pendingTools = mutableListOf<JSONObject>()

        fun flush() {
            val a = pendingAssistant
            // ⚠️ 成对才收 —— 只有 assistant 没有 tool 的话，整组丢掉
            if (a != null && pendingTools.isNotEmpty()) {
                fixed += a
                fixed += pendingTools
            }
            pendingAssistant = null
            pendingTools = mutableListOf()
        }

        messages.forEach { msg ->
            when {
                msg.optString("role") == "tool" -> {
                    // 只有前面挂着 assistant(tool_calls) 时才收
                    if (pendingAssistant != null) pendingTools += msg
                    // 孤立的 tool 直接丢
                }

                hasToolCalls(msg) -> {
                    flush()
                    pendingAssistant = msg
                }

                else -> {
                    flush()
                    fixed += msg
                }
            }
        }
        flush()

        return fixed
    }

    /** 这条消息带工具调用吗 */
    private fun hasToolCalls(m: JSONObject): Boolean {
        val tc = m.optJSONArray("tool_calls") ?: return false
        return tc.length() > 0
    }

    /* ================= ② 减半 ================= */

    /**
     * 消息太多就砍一半。
     *
     * 跟 astrbot 的 `truncate_by_halving` 一个思路：
     *   · system 消息**永远留着**
     *   · 其余砍掉一半，**留最近的**
     *   · 然后**从最近的第一条 user 开始**（保证不以下半截对话开头）
     *   · 最后过一遍 [fixMessages]
     */
    fun truncateByHalving(messages: List<JSONObject>, maxSize: Int = MAX_HISTORY): List<JSONObject> {
        if (messages.size <= maxSize) return fixMessages(messages)

        val system = messages.filter { it.optString("role") == "system" }
        val rest = messages.filter { it.optString("role") != "system" }

        val toDelete = rest.size / 2
        var kept = rest.drop(toDelete)

        // 从最近的第一条 user 开始 —— 不然会从"下半截对话"开头，模型会懵
        val idx = kept.indexOfFirst { it.optString("role") == "user" }
        if (idx > 0) kept = kept.drop(idx)

        // 一条 user 都没有的话（全是 tool/assistant），保底留个提示，
        // 不然有的 API 直接拒
        val ensured = if (kept.none { it.optString("role") == "user" }) {
            listOf(
                JSONObject()
                    .put("role", "user")
                    .put("content", "（之前的内容太长，已经被裁掉了）")
            ) + kept
        } else kept

        return fixMessages(system + ensured)
    }

    /* ================= ③ 工具结果限长 ================= */

    /**
     * 工具结果太长的话，留头留尾。
     *
     * 为什么留头尾：**头是命令、尾是报错**，中间一大坨通常没用。
     * astrbot 那边也是这么干的（超过 27500 token 只留 preview）。
     */
    fun clampToolResult(text: String): String {
        if (text.length <= TOOL_RESULT_MAX_CHARS) return text

        val head = TOOL_RESULT_PREVIEW_CHARS / 2
        val tail = TOOL_RESULT_PREVIEW_CHARS / 2
        val dropped = text.length - head - tail

        return buildString {
            append(text.take(head))
            append("\n\n…（中间省略 ").append(dropped).append(" 个字符）…\n\n")
            append(text.takeLast(tail))
        }
    }

    /**
     * 粗估一段文字要多少 token。
     *
     * 中文 1 字 ≈ 0.7 token，英文 4 字符 ≈ 1 token。
     * 取个折中：**1 token ≈ 1.5 字符**。
     * 不精确，但用来判断"该不该压"够了 —— astrbot 那个精确版要挂 tokenizer。
     */
    fun estimateTokens(text: String): Int = (text.length / 1.5).toInt()

    /** 现在用了多少（粗略） */
    fun estimateUsage(messages: List<JSONObject>, limit: Int): Float {
        if (limit <= 0) return 0f
        val used = messages.sumOf { estimateTokens(it.toString()) }
        return (used.toFloat() / limit).coerceIn(0f, 1f)
    }

    /**
     * 该压了吗。
     *
     * astrbot 的阈值是 **0.82**（用到 82% 才压）——
     * 别一超就压，那样每轮都在压，反而费。
     */
    fun shouldCompress(messages: List<JSONObject>, contextLimit: Int): Boolean =
        estimateUsage(messages, contextLimit) > COMPRESS_THRESHOLD
}
