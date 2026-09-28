package lo.naui.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    var maxTokens by mutableStateOf(2048)
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_agent", Context.MODE_PRIVATE)
        sp = p
        baseUrl = p.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
        apiKey = p.getString("api_key", "") ?: ""
        model = p.getString("model", "deepseek-chat") ?: "deepseek-chat"
        systemPrompt = p.getString("system_prompt", "") ?: ""
        temperature = p.getFloat("temperature", 0.7f)
        maxTokens = p.getInt("max_tokens", 2048)
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

    fun updateSystemPrompt(v: String) {
        systemPrompt = v
        sp?.edit()?.putString("system_prompt", v)?.apply()
    }

    fun updateTemperature(v: Float) {
        temperature = v.coerceIn(0f, 2f)
        sp?.edit()?.putFloat("temperature", temperature)?.apply()
    }

    fun updateMaxTokens(v: Int) {
        maxTokens = v.coerceIn(64, 32768)
        sp?.edit()?.putInt("max_tokens", maxTokens)?.apply()
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
