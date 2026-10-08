package lo.naui.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * Agent 的配置（单独一份 SharedPreferences，跟主题分开存）。
 *
 * 用 object + mutableStateOf：配置页改完，聊天页那边当场就变，
 * 不用回传也不用重启页面。
 */
object AgentStore {

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"

    private var sp: SharedPreferences? = null

    var baseUrl by mutableStateOf(DEFAULT_BASE)
        private set
    var apiKey by mutableStateOf("")
        private set
    var model by mutableStateOf("deepseek-chat")
        private set
    var systemPrompt by mutableStateOf("")
        private set
    var temperature by mutableStateOf(0.7f)
        private set
    /**
     * 最大回复长度。
     *
     * **0 = 不限**，这时候请求里**不带 max_tokens 字段**。
     * （以前是塞个 1000000 当"不限"，结果有些家直接报错 ——
     * 超过它自己的上限就拒绝，所以改成"干脆不带"。）
     */
    var maxTokens by mutableStateOf(0)
        private set

    /**
     * 一次对话最多让它调几轮工具。
     *
     * 以前写死 8，一撞到就卡在"工具已经使用 8 次"。现在可配，**0 = 不限**。
     */
    var maxToolRounds by mutableStateOf(16)
        private set

    /**
     * 模型上下文长度（token，粗略估：1 token ≈ 1.5 字符）。
     *
     * 这是"该不该压上下文"的分母 —— 没有它，shouldCompress 就只能是个死函数
     * （1.02.0 之前就是这样：COMPRESS_THRESHOLD=0.82 写得挺像样，**没人调用过**）。
     * 默认按 64k 给，DeepSeek / 通义那几家常用档都够得着。
     * 设太小会过早压缩（丢细节），设太大有服务商直接报 400 —— 所以做成可选项。
     */
    var contextLimit by mutableStateOf(64_000)
        private set

    fun updateContextLimit(v: Int) {
        contextLimit = if (v <= 0) 64_000 else v
        sp?.edit()?.putInt("context_limit", contextLimit)?.apply()
    }

    fun updateMaxToolRounds(v: Int) {
        // **0 或负数 = 不限轮数**（用户要的"无限工具使用次数"）。
        // 真·无限是没有的 —— AgentChat 那边压了个 500 的硬顶，
        // 免得模型自己绕死把手机跑烫。
        maxToolRounds = if (v <= 0) 0 else v
        sp?.edit()?.putInt("max_tool_rounds", maxToolRounds)?.apply()
    }

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_agent", Context.MODE_PRIVATE)
        sp = p
        baseUrl = p.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
        apiKey = p.getString("api_key", "") ?: ""
        model = p.getString("model", "deepseek-chat") ?: "deepseek-chat"
        systemPrompt = p.getString("system_prompt", "") ?: ""
        temperature = p.getFloat("temperature", 0.7f)
        maxTokens = p.getInt("max_tokens", 0)
        maxToolRounds = p.getInt("max_tool_rounds", 16)
        contextLimit = p.getInt("context_limit", 64_000)
        env = p.getString("env", AgentEnv.Sandbox.id)?.let { AgentEnv.of(it) } ?: AgentEnv.Sandbox
        workspace = p.getString("workspace", "") ?: ""
        dangerPolicy = p.getString("danger", "ask")
            ?.let { id -> DangerGuard.Policy.entries.firstOrNull { it.id == id } }
            ?: DangerGuard.Policy.Ask
        loadPersonas(p)
    }

    fun updateBaseUrl(v: String) {
        baseUrl = v.trim().ifBlank { DEFAULT_BASE }
        sp?.edit()?.putString("base_url", baseUrl)?.apply()
    }

    fun updateApiKey(v: String) {
        apiKey = v.trim()
        sp?.edit()?.putString("api_key", apiKey)?.apply()
    }

    fun updateModel(v: String) {
        model = v.trim().ifBlank { "deepseek-chat" }
        sp?.edit()?.putString("model", model)?.apply()
    }

    /** 老接口：留着给别处用，改的是当前人格 */
    fun updateSystemPrompt(v: String) {
        systemPrompt = v
        sp?.edit()?.putString("system_prompt", v)?.apply()
        savePersona(persona.copy(prompt = v))
    }

    fun updateTemperature(v: Float) {
        temperature = v.coerceIn(0f, 2f)
        sp?.edit()?.putFloat("temperature", temperature)?.apply()
    }

    fun updateMaxTokens(v: Int) {
        // 0 就是"不限"；其它值保个下限
        maxTokens = if (v <= 0) 0 else v.coerceAtLeast(256)
        sp?.edit()?.putInt("max_tokens", maxTokens)?.apply()
    }

    /**
     * 工作区。
     *
     * 用户要求"Agent 设置可设置工作区（文件夹），使 Agent 有存文件的地方，
     * 所有会话共享" —— 就是一个所有会话都认的目录。
     */
    var workspace by mutableStateOf("")
        private set

    fun updateWorkspace(path: String) {
        workspace = path.trim()
        sp?.edit()?.putString("workspace", workspace)?.apply()
    }

    /** 模型干活用的环境：沙箱 or 本机 root */
    var env by mutableStateOf(AgentEnv.Sandbox)
        private set

    fun updateEnv(v: AgentEnv) {
        env = v
        sp?.edit()?.putString("env", v.id)?.apply()
    }

    /** 危险动作怎么办（su / rm -rf 这些） */
    var dangerPolicy by mutableStateOf(DangerGuard.Policy.Ask)
        private set

    fun updateDangerPolicy(v: DangerGuard.Policy) {
        dangerPolicy = v
        sp?.edit()?.putString("danger", v.id)?.apply()
    }

    /* ---------- 人格 ---------- */

    /** 一个人格：名字 + 提示词 */
    var personas by mutableStateOf<List<Persona>>(emptyList())
        private set

    var personaId by mutableStateOf("")
        private set

    val persona: Persona
        get() = personas.firstOrNull { it.id == personaId }
            ?: personas.firstOrNull()
            ?: Persona.DEFAULT

    /** 当前生效的系统提示词 */
    /**
     * 当前生效的系统提示词 = 人格 + 工具说明（+ 工作区路径）。
     */
    val systemPromptNow: String
        // ⚠️ 是 `get() {` 不是 `get() = {`
        // —— 后者返回的是个 lambda，类型对不上（报 "expected String, actual () -> String"）
        get() {
            val ws = workspace
            return persona.prompt + TOOL_NOTE + (if (ws.isBlank()) "（没设）" else ws)
        }

    /**
     * 不管人格写成什么样，末尾都接上这段。
     *
     * 用户反馈：AI 一碰到"删文件"这种就自己怂了，说"这太危险我不做"。
     * 得让它知道 —— **有拦截机制在替我兜底**，该动手就动手。
     */
    private const val TOOL_NOTE = """

---
系统说明（不是人格的一部分）：

**动手**：你有工具，能在这台设备上真执行命令、读写文件。该动手就动手，别拿"这可能有风险"当理由推脱 —— 删除、写分区、改系统这类危险动作，设备这边会拦下来弹给用户确认，危险与否不用你替他操心。等确认是**正常流程**，不是出错；用户拒绝了就换个安全的办法，或者把为什么非这么做讲清楚。别把"为了安全我不执行"当成回答。

**省来回**（用户反馈过"一次回答用了 200 多次思考和工具"）：
1. 连着几步活用 `batch_shell` 一次提交，别 ls / cat / grep 分三次发
2. 想看多个文件用 `read_many`，别一个个 `read_file`
2b. **动文件/看结构/搜内容用专门工具，别拼 shell**（tree、file_op、archive、stat、
   delete_path、clipboard、notify、open_app…）。三个实在理由：
   拼 shell **不过路径围栏**（那沙箱就是虚的）、得赌设备上有没有 busybox、
   失败了只回一句"执行失败"害你换写法硬撞。专门工具的报错是会说话的。
3. 不知道东西在哪先用 `find_files` / `grep_text`，别一层层 ls 翻
4. 同一条命令失败别换个写法硬撞：看清报错再改，三次不行就停下来问用户
5. 做完就回答，别"顺手再看一眼""顺便验证一下"地拖

**沙箱**：默认只能在 app 自己目录里动。工具说"这路径在沙箱外面，让用户切到本机（root）"
时**别再换写法重试** —— 那是环境不对，不是命令写错。

**工作区**：用户可能在 Agent 设置里指定一个文件夹（所有会话共用），要存东西优先放那儿，别乱扔到系统目录。路径见下面这行：

【工作区】

**下载**：下东西用 `start_download` —— 它是后台下载，**秒回一个 id**，下多久都不占这条工具调用；要看进度发一条 `check_download` 问一句，别原地反复问。
别用 `run_shell` 跑 curl/wget 等大文件：命令有超时，大文件必然被掐断，你看不出是被掐了就会一直重试（这台机器真出过 51 次工具 30 次思考还没下完）。只有几百 KB 以内的小文件才用 curl，且务必带进度参数（`-#` / `--show-progress`），设备那边才能把百分比和速度显示出来。
"""

    fun selectPersona(id: String) {
        personaId = id
        sp?.edit()?.putString("persona", id)?.apply()
    }

    fun savePersona(p: Persona) {
        val next = personas.toMutableList()
        val i = next.indexOfFirst { it.id == p.id }
        if (i >= 0) next[i] = p else next += p
        personas = next
        personaId = p.id
        persistPersonas()
    }

    fun deletePersona(id: String) {
        if (personas.size <= 1) return
        personas = personas.filter { it.id != id }
        if (personaId == id) personaId = personas.first().id
        persistPersonas()
    }

    private fun persistPersonas() {
        runCatching {
            val arr = JSONArray()
            personas.forEach { arr.put(it.toJson()) }
            sp?.edit()
                ?.putString("personas", arr.toString())
                ?.putString("persona", personaId)
                ?.apply()
        }
    }

    private fun loadPersonas(p: android.content.SharedPreferences) {
        val list = runCatching {
            val arr = JSONArray(p.getString("personas", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i -> Persona.from(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList())

        personas = if (list.isEmpty()) listOf(Persona.DEFAULT) else list
        personaId = p.getString("persona", "")?.takeIf { id -> personas.any { it.id == id } }
            ?: personas.first().id
    }

    /** 当前打开的是哪个会话（null = 还没挑，进去自己挑最近一个或者新建） */
    var activeConvId by mutableStateOf<String?>(null)
        private set

    fun openConv(id: String) {
        activeConvId = id
    }

    /** 开一个新会话（只是个新 id，真正落盘在第一次发消息时） */
    fun beginNewConv() {
        activeConvId = lo.naui.agent.ChatDb.newId()
    }

    /** 当前选中的服务商 id */
    var providerId by mutableStateOf("deepseek")
        private set

    fun applyProvider(p: Provider) {
        providerId = p.id
        sp?.edit()?.putString("provider", p.id)?.apply()
        if (p.baseUrl.isNotBlank()) updateBaseUrl(p.baseUrl)
        if (p.model.isNotBlank()) updateModel(p.model)
    }

    /** 填没填 key —— 没填就没法发 */
    val ready: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()
}

/** 一个服务商预设：选它就把 Base URL 和默认模型填上，跟 AstrBot 里加提供商一个意思 */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val note: String = "",
)

/** 都是 OpenAI 兼容的，所以换个 base 就能换家 */
val PROVIDERS: List<Provider> = listOf(
    Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "官方直连"),
    Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "官方直连"),
    Provider("moonshot", "Kimi（月之暗面）", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    Provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    Provider("dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Provider("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
    Provider("ollama", "Ollama（本地）", "http://127.0.0.1:11434/v1", "llama3.1", "跑在本机或局域网"),
    Provider("custom", "自定义", "", "", "自己填 Base URL 和模型"),
)
