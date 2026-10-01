package lo.naui.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 正在跑的那个 Agent 任务的状态。
 *
 * 用户抱怨的场景：发一个要想很久的任务，切去主页 / 甚至退出去再进来，
 * 回来就只剩自己那条消息了，进程和状态全看不到。
 *
 * 所以任务的**运行状态放在这儿**（进程级单例），界面只是"看"它 ——
 * 切页不会打断、回来还能看到"还在想第 3 轮"。
 *
 * 真正的执行放在 [AgentTaskService]（前台服务）里，那是进程被杀也不容易死的地方。
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
        val steps: List<AgentChat.ToolStep> = emptyList(),
    )

    data class State(
        val running: Boolean = false,
        /** 跑的是哪个会话 */
        val conversationId: String = "",
        /** 现在到哪一步了（"第 3 轮…" / "正在用 执行命令 …"） */
        val progress: String = "",
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
    )

    /** 界面盯着这个 */
    var state by mutableStateOf(State())
        private set

    /* ---------- 流式：边生成边给界面看 ---------- */

    /** 正在生成的正文（还没定稿那一份） */
    var streamingText by mutableStateOf("")
        private set

    /** 正在生成的思考内容 */
    var streamingReasoning by mutableStateOf("")
        private set

    /** 这一轮已经想了几次 */
    var streamingRounds by mutableStateOf(0)
        private set

    /** 流式的中间记录：工具步骤（界面实时显示） */
    var streamingSteps by mutableStateOf<List<String>>(emptyList())
        private set

    fun beginStream() {
        streamingText = ""
        streamingReasoning = ""
        streamingRounds = 0
        streamingSteps = emptyList()
    }

    fun appendReasoning(s: String) {
        streamingReasoning += s
    }

    fun appendText(s: String) {
        streamingText += s
    }

    fun setRounds(n: Int) {
        streamingRounds = n
    }

    fun addStep(line: String) {
        streamingSteps = streamingSteps + line
    }

    /**
     * 有个危险动作正等着用户点。
     *
     * 任务跑在服务里，弹窗归界面管 —— 所以中间得有这么个槽：
     * 服务把"要确认什么"放这儿然后挂起，界面看到就弹窗，
     * 用户点完把答案塞回去。
     */
    var pendingConfirm by mutableStateOf<ConfirmRequest?>(null)

    data class ConfirmRequest(
        val hit: DangerGuard.Hit,
        val answer: kotlinx.coroutines.CompletableDeferred<Boolean>,
    )

    /** 界面点了之后调它 */
    fun answerConfirm(ok: Boolean) {
        pendingConfirm?.answer?.complete(ok)
        pendingConfirm = null
    }

    /** 跑完的结果先堆在这儿，界面回来时取走 */
    var pendingResult by mutableStateOf<Result?>(null)
        private set

    fun begin(conversationId: String) {
        state = State(
            running = true,
            conversationId = conversationId,
            progress = "在想…",
            startedAt = System.currentTimeMillis(),
        )
        pendingResult = null
    }

    fun progress(text: String) {
        if (!state.running) return
        state = state.copy(progress = text)
    }

    fun finish(result: Result) {
        state = state.copy(
            running = false,
            progress = "",
            finishedAt = System.currentTimeMillis(),
        )
        pendingResult = result
    }

    /** 界面把结果取走（取完就不再重复弹） */
    fun takeResult(): Result? {
        val r = pendingResult
        pendingResult = null
        return r
    }

    /**
     * 手动叫停。
     *
     * 卡住的时候总得有条路 —— 用户点了「停」就把状态收掉，
     * 服务那边下一次 onProgress 会因为 `!state.running` 直接跳过。
     */
    fun cancel() {
        if (!state.running) return
        val convId = state.conversationId
        state = state.copy(running = false, progress = "", finishedAt = System.currentTimeMillis())
        pendingResult = Result(
            conversationId = convId,
            reply = "",
            toolLog = emptyList(),
            thinkRounds = 0,
            usage = AgentApi.Usage(),
            ok = false,
            error = "你自己叫停了这次任务",
        )
    }

    /** 这个任务跑了多久了（毫秒） */
    val runningForMs: Long
        get() = if (state.running && state.startedAt > 0) {
            System.currentTimeMillis() - state.startedAt
        } else {
            0L
        }

    val elapsedMs: Long
        get() = if (state.running && state.startedAt > 0) {
            System.currentTimeMillis() - state.startedAt
        } else {
            0L
        }

    /* ---------------- 开关 ---------------- */

    private var sp: android.content.SharedPreferences? = null

    /** 后台留存：用前台服务托着，切页/切后台都不断 */
    var keepAlive by mutableStateOf(true)
        private set

    /** 开机自启（把服务拉起来） */
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
