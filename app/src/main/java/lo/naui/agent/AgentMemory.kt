package lo.naui.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 上下文能力：召回、压缩、任务态。
 *
 * ## 为什么单独一个文件
 *
 * 原来 AgentContext 里那套是照抄 astrbot 的**裁剪**逻辑，能用的只有
 * `truncateByHalving`（超了砍一半）和 `clampToolResult`（工具结果留头留尾）。
 * 而 `shouldCompress` / `COMPRESS_THRESHOLD` 这两个"看起来在做压缩"的东西
 * **全项目没有调用方** —— 属于装饰性代码：阈值调它也不会有任何行为变化。
 *
 * 这儿把三件真事接上：
 *
 * 1. **召回**（[recall]）：以前是死板的"最后 30 条"，用户第 40 轮提到
 *    第 5 轮说过的东西，模型是真的没看见 —— 它只能答"你之前没提过"。
 *    现在最近 N 条一定带上，再从更早的里面按关键词捞几条回来。
 * 2. **压缩**（[compact]）：不是删掉，是把**被挤出去的那些轮次**压成一小段
 *    结构化摘要（谁调了什么工具、成没成、用户说过什么关键点），
 *    以 system 补充块的形式继续留着。丢内容变成丢字节。
 * 3. **任务态**（[taskBoard]）：本轮已经干了哪些事、成了几次、失败了哪些、
 *    在等什么确认 —— 每轮重新算一次塞进 system。
 *    这是最省 token 也最有效的一条：模型重复调同一个工具、
 *    撞同一个错，多半不是"笨"，是它**看不见自己已经做过什么**。
 */
object AgentMemory {

    /** 最近这么多条**一定**带上（不管关键词命不命中） */
    private const val RECENT_KEEP = 14

    /** 从更早的历史里最多再捞几条 */
    private const val RECALL_EXTRA = 8

    /** 关键词命中至少要匹配上这么几个字 */
    private const val MIN_PHRASE = 2

    /* ==================== ① 召回 ==================== */

    /**
     * 挑要发给模型的历史。
     *
     * 规则很简单：**最近 RECENT_KEEP 条全要**（对话的连贯性在那），
     * 更早的按跟"当前这句问话"的关键词重合度打分，取前 [RECALL_EXTRA] 条，
     * 再按原始顺序插回去 —— 顺序乱了模型会理解错因果。
     *
     * ⚠️ 只挑**完整轮次**：一条 assistant 带着 tool_calls，后面必须紧跟它的
     * tool 结果，不能拆开塞 —— 拆了就是 OpenAI 规范里的 400（坑：fixMessages 管的就是这个）。
     * 所以这里按"user 开头的轮"分组，整组进整组出。
     *
     * @param query 当前这句用户的话（关键词从这儿来）
     * @return 挑好的消息（原始顺序）
     */
    fun recall(raw: List<JSONObject>, query: String): List<JSONObject> {
        if (raw.size <= RECENT_KEEP) return raw
        val recent = raw.takeLast(RECENT_KEEP)
        val older = raw.dropLast(RECENT_KEEP)

        // 切成轮次：每个 user 开一个新轮，assistant + tool 结果都算在它里面
        val turns = ArrayList<List<JSONObject>>()
        var cur = ArrayList<JSONObject>()
        older.forEach { m ->
            if (m.optString("role") == "user" && cur.isNotEmpty()) {
                turns += cur.toList(); cur = ArrayList()
            }
            cur.add(m)
        }
        if (cur.isNotEmpty()) turns += cur.toList()

        val keys = keywords(query)
        if (keys.isEmpty() || turns.isEmpty()) return recent

        val scored = turns.map { turn ->
            turn to turn.sumOf { msg ->
                val text = (msg.optString("content") + " " + msg.toString()).lowercase()
                keys.count { k -> text.contains(k) }
            }
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(RECALL_EXTRA)
            .map { it.first }

        if (scored.isEmpty()) return recent
        // 按原始位置排回去（sortedBy 是稳定的，取每轮第一个元素的索引当位置）
        val picked = scored.sortedBy { turn -> older.indexOf(turn.first()) }
        return picked.flatten() + recent
    }

    /**
     * 从一句话里取关键词。
     *
     * 中文没有空格，所以按**二元组**切（"液态玻璃" → 液态/态玻/玻璃），
     * 英文数字按词切。够糙，但召回场景要的只是"提到过同一件事"，
     * 不是搜索引擎。
     */
    fun keywords(q: String): List<String> {
        val out = LinkedHashSet<String>()
        val s = q.lowercase()
        // 拉丁词
        Regex("[a-z0-9_./\\-]{2,}").findAll(s).forEach { out += it.value }
        // 中文二元组
        val han = s.filter { it.code in 0x4E00..0x9FFF }
        if (han.length >= MIN_PHRASE) {
            for (i in 0 until han.length - 1) out += han.substring(i, i + 2)
        }
        if (han.length >= MIN_PHRASE) out += han.take(4)
        return out.toList()
    }

    /* ==================== ② 压缩 ==================== */

    /**
     * 上下文快满了：把**最老的若干整轮**换成一小段摘要。
     *
     * @param limit 模型上下文长度（token，粗略）
     * @return 新的消息列表 + 一段要拼进 system 的摘要（没压成就原样返回 + 空串）
     */
    fun compact(
        messages: List<JSONObject>,
        limit: Int,
    ): Pair<List<JSONObject>, String> {
        if (limit <= 0) return messages to ""
        // 走 AgentContext.shouldCompress —— 别在这儿重抄一遍阈值判断，
        // 那个函数本来就是死代码（写了个 0.82 摆着，全项目没人调用），
        // 现在它是唯一入口，阈值只在 AgentContext 里定义一次。
        if (!AgentContext.shouldCompress(messages, limit)) {
            return messages to ""
        }

        // 留最近这么多条，其余全部并进摘要
        val keepFrom = maxOf(0, messages.size - RECENT_KEEP)
        val dropped = messages.take(keepFrom)
        val kept = messages.drop(keepFrom)
        if (dropped.isEmpty()) return messages to ""

        val summary = digestTurns(dropped)
        val out = kept.filter { !isPureSystemNoise(it) }
        // 摘要得挂成一条 user 消息 —— 有的服务商不吃"中途插 system"
        val head = JSONObject()
            .put("role", "user")
            .put(
                "content",
                "【前面这段对话的摘要，之前 $keepFrom 条已经不在上下文里了】\n$summary\n" +
                    "（需要摘要里没提到的细节，就跟用户确认，别猜。）"
            )
        return (listOf(head) + out) to summary
    }

    /** 工具结果、system 这类不参与摘要的噪音 */
    private fun isPureSystemNoise(m: JSONObject): Boolean =
        m.optString("role") == "system"

    /**
     * 把一堆消息压成结构化摘要。
     *
     * 刻意**不调模型再来一次总结**（那是额外一次请求 + 会幻觉），
     * 只抽事实：用户说过什么（截断）、动过哪些工具、成没成。
     * 模型丢的是字节，不是"这事儿发生过"。
     */
    private fun digestTurns(msgs: List<JSONObject>): String {
        val sb = StringBuilder()
        var userN = 0
        var toolN = 0
        val tools = LinkedHashMap<String, Int>()   // 工具名 → 调用次数
        val fails = ArrayList<String>()

        msgs.forEach { m ->
            when (m.optString("role")) {
                "user" -> {
                    val t = m.optString("content").replace("\n", " ").trim()
                    if (t.isNotBlank() && !t.startsWith("【")) {
                        userN++
                        sb.append("· 用户说过：").append(t.take(90)).append('\n')
                    }
                }
                "assistant" -> {
                    m.optJSONArray("tool_calls")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val fn = arr.optJSONObject(i)?.optJSONObject("function") ?: continue
                            val name = fn.optString("name")
                            if (name.isBlank()) continue
                            tools[name] = (tools[name] ?: 0) + 1
                            toolN++
                        }
                    }
                }
                "tool" -> {
                    val c = m.optString("content")
                    // 出错的才值得留 —— "出错了：" 是 AgentChat 拼上去的前缀
                    if (c.startsWith("出错了：") || c.contains("失败")) {
                        fails += c.replace("\n", " ").take(80)
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            sb.append("· 动过工具 ").append(toolN).append(" 次：")
                .append(tools.entries.joinToString("、") { "${it.key}×${it.value}" })
                .append('\n')
        }
        if (fails.isNotEmpty()) {
            sb.append("· 其中失败过：").append(fails.take(6).joinToString("；").take(400)).append('\n')
        }
        // ⚠️ 用 length 而不是 sb.isEmpty()：CharSequence.isEmpty() 是 Java 15 才有的
        // 默认方法，compileSdk 高编得过，但 minSdk 26 的机器上运行时会 NoSuchMethodError。
        if (sb.length == 0) sb.append("（这段没有实义内容，多半是闲聊或被丢弃的尝试）")
        return sb.toString().take(1800)
    }

    /* ==================== ③ 任务态 ==================== */

    /**
     * 本轮的任务状态板，拼到 system 后面。
     *
     * 这块是**每轮重算**的，所以永远反映"现在"：
     *   · 这一轮已经动过哪些工具、各成功还是失败（防止重复劳动）
     *   · 连续失败的是什么（防止换写法硬撞）
     *   · 有没有正卡在等用户确认
     *   · 上下文压没压过（压过就得提醒它"前面有摘要，别当失忆"）
     *
     * 用户提的"AI 反复做同一件事"，根因多数就在这儿：模型看不见自己做过什么。
     */
    fun taskBoard(
        steps: List<ToolStep>,
        round: Int,
        compressed: Boolean,
        waitingConfirm: Boolean,
    ): String {
        if (steps.isEmpty() && !compressed && !waitingConfirm) return ""
        val sb = StringBuilder("\n\n【本轮状态（系统自动生成，不是用户说的）】")
        sb.append("\n第 ").append(round).append(" 轮，已经做过 ").append(steps.size).append(" 个动作：")
        steps.takeLast(12).forEachIndexed { i, s ->
            sb.append('\n').append(i + 1).append(") ")
                .append(s.label).append(" · ").append(s.brief.take(60))
                .append(if (s.ok) " · 成" else " · ✗败").append(" · ").append(s.millis).append("ms")
        }
        if (steps.size > 12) sb.append("\n…（前面还有 ").append(steps.size - 12).append(" 个）")

        // 同一个失败重复出现 —— 单独点出来，比让它自己数强
        val failed = steps.filter { !it.ok }.map { it.name }
        if (failed.isNotEmpty()) {
            val top = failed.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (top != null && top.value >= 2) {
                sb.append("\n⚠️ 「").append(top.key).append("」这轮已经失败 ")
                    .append(top.value).append(" 次。**别再换写法重试** —— ")
                    .append("要么读一下报错想个不同办法，要么把情况说给用户。")
            }
        }
        if (waitingConfirm) sb.append("\n⏸ 有个动作正在等用户点确认，同意后会**自动执行**，不用你再发一遍。")
        if (compressed) sb.append("\n📉 上下文太长，前面若干轮已经压成摘要了 —— 摘要里没有的细节，问用户，别猜。")
        sb.append("\n（所以：已经成功做过的事别重做，报成功的工具别再调第二次。）")
        return sb.toString()
    }

    /* ==================== ④ 失败沉淀 ==================== */

    /**
     * 会话级的失败记录：同一个工具+同一类错，只记一次。
     *
     * 为什么要它：长会话里模型会在同一个坑上来回撞 ——
     * 因为压缩会把"上次撞了"这件事挤掉，而报错文本本身没有跨轮的记忆。
     * 存这儿，靠 [AgentTaskStore] 按会话分开，切会话不会串。
     */
    class LessonLog {
        private val seen = LinkedHashMap<String, String>()   // key = 工具+错误签名

        fun note(tool: String, output: String) {
            if (seen.size >= 12) seen.remove(seen.keys.first())
            val first = output.replace("\n", " ").trim().take(120)
            val sig = tool + "::" + first.take(48)
            seen[sig] = tool + "：" + first
        }

        fun render(): String = if (seen.isEmpty()) "" else
            "\n\n【这个会话里已经失败过的做法（别再试同样的）】\n" +
                seen.values.joinToString("\n") { "· " + it }.take(1200)

        fun isEmpty() = seen.isEmpty()
    }
}
