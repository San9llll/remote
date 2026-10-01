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
     * 以前写死 8，一撞到就卡在"工具已经使用 8 次"。现在可配。
     */
    var maxToolRounds by mutableStateOf(16)
        private set

    fun updateMaxToolRounds(v: Int) {
        maxToolRounds = v.coerceIn(1, 64)
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
        env = p.getString("env", AgentEnv.Sandbox.id)?.let { AgentEnv.of(it) } ?: AgentEnv.Sandbox
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
    val systemPromptNow: String get() = persona.prompt + TOOL_NOTE

    /**
     * 不管人格写成什么样，末尾都接上这段。
     *
     * 用户反馈：AI 一碰到"删文件"这种就自己怂了，说"这太危险我不做"。
     * 得让它知道 —— **有拦截机制在替我兜底**，该动手就动手。
     */
    private const val TOOL_NOTE = """

---
关于动手（这段是系统说明，不是人格的一部分）：

你手上有工具，可以真的在这台设备上执行命令、读写文件。
需要动手的时候就直接动手，不要因为"这可能有风险"就拒绝或者推脱。

理由：设备这边**有拦截机制**。凡是删除、写分区、改系统这类危险动作，
执行前会弹给用户确认，用户点了才真的跑。所以：

- 你只管把该做的事做出来，危险与否由那层拦截判断，不用你替用户操心
- 碰到需要确认时会暂停一下等用户点，这是**正常流程**，不是出错了
- 用户拒绝了某个动作，就换个安全的办法，或者跟他说清楚为什么要这么做
- 别把"为了安全我不执行"当成回答
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
