package lo.naui.agent

/**
 * 安全逻辑：类型化的"要用户点头"决策。
 *
 * ## 换掉的是什么
 *
 * 旧写法是把决策**塞进 output 文本**：
 *
 * ```kotlin
 * ToolResult(false, NEED_ROOT_PREFIX + command + "|" + reason)   // "__NEED_ROOT__|xx|yy"
 * ```
 *
 * 然后上层 `result.output.startsWith(NEED_ROOT_PREFIX)` 再 substringBefore('|') 拆回来。
 * 三个实在的问题：
 *
 * 1. **命令里含 `|` 就串味** —— AgentEnv 里那两个 `replace("|", " ")` 就是在补这个洞，
 *    补完显示出来的命令已经不是用户批准的那条了；
 * 2. **ok=false 和"待批准"挤在同一个字段里** —— 上层靠前缀区分"真失败"和"要问人"，
 *    模型看到的报错文本还带着内部标记；
 * 3. **加一类拦截就要改一次字符串协议** —— 删除、写系统区这些新场景没法叠上去。
 *
 * 现在：`ToolResult` 多一个可空的 `decision` 字段，
 * 要问人就带一个 [RiskDecision] 对象上来，**要执行的活儿封在 run 里**，
 * 用户点同意才跑；不同意就不碰任何东西。弹窗通道全项目只有 [AgentTaskStore.pendingConfirm] 一个。
 */
enum class RiskKind(val id: String, val title: String) {
    /** Agent 明说这条必须用 root */
    Root("root", "它想用 root"),

    /** 命中 DangerGuard 那九类危险动作 */
    Danger("danger", "危险请求"),

    /** 删除类：工具化以后模型删得更顺手，风险反而更高，所以单独一类 */
    Delete("delete", "它想删东西"),

    /** 写到 /sdcard 之外 */
    WriteOutside("write_outside", "它想改 sdcard 之外");

    companion object {
        fun of(id: String?): RiskKind = entries.firstOrNull { it.id == id } ?: Danger
    }
}

/**
 * 一个待批准的动作。
 *
 * @param detail 给人看的**具体内容**：要跑的命令原文 / 要删的路径+规模。
 *   用户的要求是"提示中间是 agent 具体需要执行的东西"，所以这里必须是原文，
 *   不能是"它想执行一些操作"这种含糊话。
 * @param reason Agent 自己给的理由（run_shell 的 reason 参数），单独一块显示。
 * @param run 同意之后真正执行的那段活儿 —— 之前**不许碰任何东西**。
 */
data class RiskDecision(
    val kind: RiskKind,
    val detail: String,
    val reason: String = "",
    val note: String = "",
    val consequence: String = "",
    val run: suspend () -> ToolResult,
) {
    /** 弹窗标题：root 类和删除类各说各的话，别混成一句"危险请求" */
    fun title(): String = kind.title

    /** 用户拒绝时回给模型的话 —— 要能指导它下一步，不只是"不行" */
    fun refusedText(): String = when (kind) {
        RiskKind.Root ->
            "用户不同意用 root。换个不需要 root 的办法，或者跟他解释清楚为什么非要 su。"
        RiskKind.Delete ->
            "用户不同意删除。东西还在原地。换个办法（先备份 / 只删单个文件 / 让用户自己动手），" +
                "或者把为什么要删说清楚。"
        RiskKind.WriteOutside ->
            "用户不同意写到那个路径外面。改存到 /sdcard 下或沙箱里，或者先问他一句要放哪儿。"
        RiskKind.Danger ->
            "用户拒绝了这个操作（$note）。换个安全的办法，或者先问清楚。"
    }
}
