package lo.naui.book

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** TTS 的两种协议 */
enum class TtsProtocol {
    /** chat/completions 的变体：messages + audio 字段，音频在 base64 里回来（小米 MiMo 就是这种） */
    ChatAudio,

    /** 标准 OpenAI 那套：POST /v1/audio/speech，直接回二进制音频 */
    OpenAiSpeech,
}

/**
 * 一个 TTS 厂商。
 *
 * [modelsPath] 是"问它有哪些模型"的接口，[speechPath] 是合成的接口，
 * 都写成相对 baseUrl 的路径，方便换自建代理。
 */
data class TtsVendor(
    val id: String,
    val label: String,
    val baseUrl: String,
    val protocol: TtsProtocol,
    val defaultModel: String,
    val modelsPath: String = "/v1/models",
    val speechPath: String = "/v1/audio/speech",
    val chatPath: String = "/v1/chat/completions",
    /** 内置音色；用户也可以自己填 */
    val voices: List<String> = emptyList(),
    /** 音色是"名字"还是"ID"，界面上给个提示 */
    val voiceHint: String = "",
)

/**
 * 5 家厂商。
 *
 * 小米 MiMo 放第一个当默认 —— 它那个接口带「风格指令」，
 * 分角色朗读时正好用得上（旁白一种语气、角色另一种）。
 */
val TTS_VENDORS: List<TtsVendor> = listOf(
    TtsVendor(
        id = "mimo",
        label = "MiMo TTS（小米，默认）",
        baseUrl = "https://api.xiaomimimo.com",
        protocol = TtsProtocol.ChatAudio,
        defaultModel = "mimo-v2-tts",
        voices = listOf("冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
        voiceHint = "可以直接填预置音色名，也可以填你克隆出来的音色",
    ),
    TtsVendor(
        id = "openai",
        label = "OpenAI TTS",
        baseUrl = "https://api.openai.com",
        protocol = TtsProtocol.OpenAiSpeech,
        defaultModel = "gpt-4o-mini-tts",
        voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral", "sage"),
        voiceHint = "OpenAI 的固定音色名",
    ),
    TtsVendor(
        id = "dashscope",
        label = "阿里云百炼（CosyVoice）",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode",
        protocol = TtsProtocol.OpenAiSpeech,
        defaultModel = "qwen-tts",
        voices = listOf("Cherry", "Serena", "Ethan", "Chelsie"),
        voiceHint = "百炼的音色名，比如 Cherry",
    ),
    TtsVendor(
        id = "siliconflow",
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn",
        protocol = TtsProtocol.OpenAiSpeech,
        defaultModel = "FunAudioLLM/CosyVoice2-0.5B",
        voices = listOf("alex", "anna", "bella", "benjamin", "charles", "claire", "david", "diana"),
        voiceHint = "硅基流动的音色名",
    ),
    TtsVendor(
        id = "minimax",
        label = "MiniMax",
        baseUrl = "https://api.minimax.chat",
        protocol = TtsProtocol.OpenAiSpeech,
        defaultModel = "speech-01-turbo",
        voices = listOf("male-qn-qingse", "female-shaonv", "female-yujie", "male-qn-jingying"),
        voiceHint = "MiniMax 的 voice_id",
    ),
    TtsVendor(
        id = "custom",
        label = "自定义（OpenAI 兼容）",
        baseUrl = "",
        protocol = TtsProtocol.OpenAiSpeech,
        defaultModel = "tts-1",
        voiceHint = "只要是对 OpenAI /v1/audio/speech 的就填这儿",
    ),
)

/**
 * TTS 配置。
 *
 * 每个角色一个音色（旁白 / 女主 / 男主 …），合成时分开跑、拿到音频再拼起来。
 */
object TtsConfig {

    private var sp: android.content.SharedPreferences? = null

    var vendorId by mutableStateOf("mimo")
        private set
    var apiKey by mutableStateOf("")
        private set
    var baseUrlOverride by mutableStateOf("")
        private set
    var model by mutableStateOf("")
        private set
    /** 输出格式，wav 好拼接 */
    var format by mutableStateOf("wav")
        private set
    /** 倍速播放上限之外的默认语速提示（有些厂商用 speed 参数） */
    var speed by mutableStateOf(1.0f)
        private set

    /** 角色名 → 音色。旁白固定用 "旁白" 这个 key */
    var voiceMap by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    val vendor: TtsVendor get() = TTS_VENDORS.firstOrNull { it.id == vendorId } ?: TTS_VENDORS[0]

    val baseUrl: String get() = baseUrlOverride.ifBlank { vendor.baseUrl }
    val useModel: String get() = model.ifBlank { vendor.defaultModel }

    /** 旁白用一个固定 key，跟人物区分开 */
    const val NARRATOR = "旁白"

    fun voiceOf(character: String): String =
        voiceMap[character]?.takeIf { it.isNotBlank() }
            ?: voiceMap[NARRATOR]?.takeIf { it.isNotBlank() }
            ?: vendor.voices.firstOrNull()
            ?: ""

    val ready: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_tts", Context.MODE_PRIVATE)
        sp = p
        vendorId = p.getString("vendor", "mimo") ?: "mimo"
        apiKey = p.getString("key", "") ?: ""
        baseUrlOverride = p.getString("base", "") ?: ""
        model = p.getString("model", "") ?: ""
        format = p.getString("format", "wav") ?: "wav"
        speed = p.getFloat("speed", 1.0f)
        voiceMap = runCatching {
            val o = JSONObject(p.getString("voices", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it, "") }
        }.getOrDefault(emptyMap())
    }

    fun updateVendor(id: String) {
        vendorId = id
        // 换厂商就把模型清掉，让它回到这家的默认
        model = ""
        sp?.edit()?.putString("vendor", id)?.putString("model", "")?.apply()
    }

    fun updateApiKey(v: String) {
        apiKey = v.trim()
        sp?.edit()?.putString("key", apiKey)?.apply()
    }

    fun updateBaseUrl(v: String) {
        baseUrlOverride = v.trim()
        sp?.edit()?.putString("base", baseUrlOverride)?.apply()
    }

    fun updateModel(v: String) {
        model = v.trim()
        sp?.edit()?.putString("model", model)?.apply()
    }

    fun updateFormat(v: String) {
        format = v
        sp?.edit()?.putString("format", v)?.apply()
    }

    fun updateSpeed(v: Float) {
        speed = v.coerceIn(0.5f, 2f)
        sp?.edit()?.putFloat("speed", speed)?.apply()
    }

    fun setVoice(character: String, voice: String) {
        val next = voiceMap.toMutableMap()
        if (voice.isBlank()) next.remove(character) else next[character] = voice
        voiceMap = next
        persist()
    }

    /** 把书里的人物 + 旁白都铺上默认音色 */
    fun ensureVoices(characters: List<BookCharacter>) {
        val next = voiceMap.toMutableMap()
        var changed = false
        (listOf(NARRATOR) + characters.map { it.name }).forEach { key ->
            if (next[key].isNullOrBlank()) {
                val pick = vendor.voices.getOrNull(
                    when {
                        key == NARRATOR -> 0
                        else -> (characters.indexOfFirst { it.name == key } + 1) % vendor.voices.size.coerceAtLeast(1)
                    }
                )
                if (!pick.isNullOrBlank()) {
                    next[key] = pick
                    changed = true
                }
            }
        }
        if (changed) {
            voiceMap = next
            persist()
        }
    }

    private fun persist() {
        runCatching {
            val o = JSONObject()
            voiceMap.forEach { (k, v) -> o.put(k, v) }
            sp?.edit()?.putString("voices", o.toString())?.apply()
        }
    }
}
