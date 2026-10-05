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
    val systemPromptNow: String get() = {
        val ws = workspace
        persona.prompt + TOOL_NOTE + (if (ws.isBlank()) "（没设）" else ws)
    }

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

关于效率（也是系统说明，**这段很重要**）：

用户反馈过"一次回答用了 200 多次思考和工具"。注意下面几条，能省掉大量来回：

1. **需要连着做几步的时候，用 `batch_shell` 一次提交**
   —— 别 `ls` 一次、`cat` 一次、`grep` 一次分开来，
   每分一次就要跟模型来回一轮，很慢

2. **想知道好几个文件里是什么 → 用 `read_many`**
   —— 别一个个 `read_file`

3. **不知道东西在哪 → 先用 `find_files` 或 `grep_text`**
   —— 别一层层 `ls` 翻、别读一堆文件碰运气

4. **同一件事别反复试**
   —— 一条命令失败了，看清楚报错再改，别换个写法硬撞；
      撞三次还不行就停下来跟用户说清楚

5. **该收就收**
   —— 事情做完了就回答，别"顺手再看一眼"、"顺便验证一下"地拖下去

关于工作区（也是系统说明）：

- 用户可以在 Agent 设置里指定一个**工作区文件夹**，所有会话共用。
- 需要存东西的时候优先放那儿，别乱扔到系统目录。
- 工作区路径见下面那行：

【工作区】

- 下大文件时**务必带上进度参数**，不然用户只能干等：
  · curl 加 `--progress-bar`（或者 `-#`）
  · wget 加 `--show-progress`
- 这样设备那边能把百分比和速度实时显示出来
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
