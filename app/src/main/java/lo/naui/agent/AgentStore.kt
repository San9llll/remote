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

    /** 填没填 key —— 没填就没法发 */
    val ready: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()
}
