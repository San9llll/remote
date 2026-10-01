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
        val toolLog: List<String>,
        val thinkRounds: Int,
        val usage: AgentApi.Usage,
        val ok: Boolean,
        val error: String = "",
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
