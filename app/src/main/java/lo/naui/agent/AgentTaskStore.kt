package lo.naui.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Agent 任务的运行状态。
 *
 * ## 为什么是"每个会话一份"
 *
 * 用户要的：会话 A 还在思考的时候切到会话 B，
 * **A 得继续在后台跑**，B 也要能自己发消息、自己思考，两边互不打扰。
 *
 * 以前这里是**全局单例**（同一时间只允许一个任务），切个会话就把前一个顶掉了。
 * 现在改成按 conversationId 分开存：
 *
 * ```
 * states["conv1"] = 正在跑第 3 轮…
 * states["conv2"] = 正在跑第 1 轮…
 * ```
 *
 * 服务那边也是每个会话起一个协程，互相不 cancel。
 */
object AgentTaskStore {

    /** 一条任务的结果，跑完塞进对话里 */
    data class Result(
        val conversationId: String,
        val reply: String,
        val toolLog: List<String> = emptyList(),
        val thinkRounds: Int = 0,
        val usage: AgentApi.Usage = AgentApi.Usage(),
        val ok: Boolean,
        val error: String = "",
        /** 思考内容，落盘之后退出再进来也能点开看 */
        val reasoning: String = "",
        /** 结构化的工具记录（带耗时），界面用 */
        val steps: List<ToolStep> = emptyList(),
    )

    data class State(
        val running: Boolean = false,
        val conversationId: String = "",
        /** 现在到哪一步了 */
        val progress: String = "",
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        /**
         * 这个会话里已经失败过的做法（工具 + 错误签名，去重）。
         *
         * 为什么挂在 State 上而不是全局：切会话不该把别的会话的教训带过来。
         * 类型是**可变的记录本**（不是 List/String 那种值），所以 data class 的
         * copy() 出来的新状态共享同一个实例 —— 记一次，后面每一轮都还在。
         */
        val lessons: AgentMemory.LessonLog = AgentMemory.LessonLog(),
    ) {
        companion object {
            val IDLE = State()
        }
    }

    /** 每个会话一份状态 */
    private var states by mutableStateOf<Map<String, State>>(emptyMap())

    /** 跑完的结果，按会话堆着 */
    private var results by mutableStateOf<Map<String, Result>>(emptyMap())

    /* ---------- 流式的中间内容，也是按会话分 ---------- */

    private var streams by mutableStateOf<Map<String, Stream>>(emptyMap())

    data class Stream(
        val text: String = "",
        val reasoning: String = "",
        val rounds: Int = 0,
        val steps: List<String> = emptyList(),
        /** 现在在跑哪个工具 */
        val tool: String = "",
        val toolHint: String = "",
        val progress: Float = -1f,
        val speed: String = "",
    ) {
        companion object {
            val EMPTY = Stream()
        }
    }

    /* ================= 读 ================= */

    fun stateOf(convId: String?): State =
        if (convId.isNullOrBlank()) State.IDLE else states[convId] ?: State.IDLE

    fun streamOf(convId: String?): Stream =
        if (convId.isNullOrBlank()) Stream.EMPTY else streams[convId] ?: Stream.EMPTY

    /** 有几个会话正在跑（侧栏/悬浮球能拿它显示个总数） */
    val runningCount: Int get() = states.values.count { it.running }

    /* ================= 写 ================= */

    fun begin(convId: String) {
        val now = System.currentTimeMillis()
        states = states + (convId to State(true, convId, "在想…", now, 0L))
        streams = streams + (convId to Stream.EMPTY)
        results = results - convId
    }

    fun progress(convId: String, text: String) {
        val s = states[convId] ?: return
        if (!s.running) return
        states = states + (convId to s.copy(progress = text))
    }

    fun finish(result: Result) {
        val id = result.conversationId
        val s = states[id]
        if (s != null) {
            states = states + (id to s.copy(
                running = false,
                progress = "",
                finishedAt = System.currentTimeMillis(),
            ))
        }
        results = results + (id to result)
    }

    /** 界面把结果取走（取完就不再重复弹） */
    fun takeResult(convId: String): Result? {
        val r = results[convId] ?: return null
        results = results - convId
        return r
    }

    /**
     * 手动叫停某个会话。
     */
    fun cancel(convId: String) {
        val s = states[convId] ?: return
        if (!s.running) return
        states = states + (convId to s.copy(
            running = false, progress = "", finishedAt = System.currentTimeMillis()
        ))
        results = results + (convId to Result(
            conversationId = convId,
            reply = "",
            ok = false,
            error = "你自己叫停了这次任务",
        ))
    }

    /* ---------- 流式内容的更新 ---------- */

    fun appendReasoning(convId: String, s: String) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(reasoning = cur.reasoning + s))
    }

    fun appendText(convId: String, s: String) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(text = cur.text + s))
    }

    fun setRounds(convId: String, n: Int) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(rounds = n))
    }

    fun addStep(convId: String, line: String) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(steps = cur.steps + line))
    }

    fun updateRunningTool(convId: String, label: String, hint: String) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(
            tool = label, toolHint = hint, progress = -1f, speed = ""
        ))
    }

    fun updateToolProgress(convId: String, p: Float) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(progress = p.coerceIn(0f, 1f)))
    }

    fun updateToolSpeed(convId: String, s: String) {
        val cur = streamOf(convId)
        streams = streams + (convId to cur.copy(speed = s))
    }

    /** 清掉某个会话的流式中间态（跑完了调） */
    fun clearStream(convId: String) {
        streams = streams - convId
    }

    /** 会话被删掉时清干净 */
    fun forget(convId: String) {
        states = states - convId
        results = results - convId
        streams = streams - convId
    }

    /* ---------------- 危险确认（本来就按会话分） ---------------- */

    var pendingConfirm by mutableStateOf<ConfirmRequest?>(null)

    /**
     * 一次待批准的请求。
     *
     * 1.02.0 重构：以前这儿塞的是 `hit: DangerGuard.Hit` 外加 command / reason / forRoot
     * 三个散装字段 —— 因为 root 那条路是从 output 里拆字符串来的，
     * 只能一项一项塞。现在全部收进一个 [RiskDecision]：
     * 标题、给人看的内容、理由、后果、**同意后要跑什么**，都在里面。
     */
    data class ConfirmRequest(
        val conversationId: String,
        val decision: RiskDecision,
        val answer: kotlinx.coroutines.CompletableDeferred<Boolean>,
    )

    fun answerConfirm(ok: Boolean) {
        pendingConfirm?.answer?.complete(ok)
        pendingConfirm = null
    }

    /* ---------------- 开关 ---------------- */

    private var sp: android.content.SharedPreferences? = null

    var keepAlive by mutableStateOf(true)
        private set

    var bootStart by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_agent_task", Context.MODE_PRIVATE)
        sp = p
        keepAlive = p.getBoolean("keep_alive", true)
        bootStart = p.getBoolean("boot_start", false)
    }

    fun updateKeepAlive(v: Boolean) {
        keepAlive = v
        sp?.edit()?.putBoolean("keep_alive", v)?.apply()
    }

    fun updateBootStart(v: Boolean) {
        bootStart = v
        sp?.edit()?.putBoolean("boot_start", v)?.apply()
    }
}
