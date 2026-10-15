package lo.naui.book

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * TTS 调用。
 *
 * 两条协议：
 *  - [TtsProtocol.ChatAudio]：小米 MiMo 那种，POST /v1/chat/completions，
 *    正文放在 assistant 消息里，风格指令放在 user 消息里，音频以 base64 回来。
 *    **带风格指令这点很有用** —— 分角色朗读时可以直接告诉它"用旁白的语气"。
 *  - [TtsProtocol.OpenAiSpeech]：标准 OpenAI 那套，POST /v1/audio/speech，回二进制。
 *
 * 「有哪些模型」走 GET /v1/models —— 用户要求先问官方要列表再让他挑。
 */
object TtsEngine {

    private const val TIMEOUT_CONNECT = 15_000
    private const val TIMEOUT_READ = 180_000

    /* ================= 模型列表 ================= */

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val vendor = TtsConfig.vendor
            val url = TtsConfig.baseUrl.trimEnd('/') + vendor.modelsPath
            val conn = open(url, "GET")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP " + code + "：" + body.take(200))
            }

            val out = mutableListOf<String>()
            runCatching {
                val arr = JSONObject(body).optJSONArray("data")
                    ?: JSONObject(body).optJSONArray("models")
                arr?.let {
                    for (i in 0 until it.length()) {
                        val o = it.optJSONObject(i)
                        val id = o?.optString("id", "") ?: it.optString(i, "")
                        if (id.isNotBlank()) out += id
                    }
                }
            }
            if (out.isEmpty()) {
                // 有些家直接给数组
                runCatching {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
            if (out.isEmpty()) throw IllegalStateException("这个接口没返回模型列表：" + body.take(200))
            out.distinct().sorted()
        }
    }

    /* ================= 合成 ================= */

    /**
     * 把一段文字念出来。
     *
     * [style] 是风格指令（只对 ChatAudio 协议有效），比如"用平静的旁白语气朗读"。
     */
    suspend fun synthesize(text: String, voice: String, style: String = ""): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) throw IllegalStateException("没东西可念")
                if (!TtsConfig.ready) throw IllegalStateException("还没填 TTS 的 Key")
                when (TtsConfig.vendor.protocol) {
                    TtsProtocol.ChatAudio -> chatAudio(text, voice, style)
                    TtsProtocol.OpenAiSpeech -> openAiSpeech(text, voice, style)
                }
            }
        }

    private fun chatAudio(text: String, voice: String, style: String): ByteArray {
        val vendor = TtsConfig.vendor
        val url = TtsConfig.baseUrl.trimEnd('/') + vendor.chatPath
        val body = JSONObject()
            .put("model", TtsConfig.useModel)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", style.ifBlank { "请朗读下面的内容。" }))
                    .put(JSONObject().put("role", "assistant").put("content", text))
            )
            .put(
                "audio",
                JSONObject()
                    .put("format", TtsConfig.format)
                    .put("voice", voice)
            )

        val conn = open(url, "POST")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("TTS HTTP " + code + "：" + raw.take(200))

        val b64 = runCatching {
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optJSONObject("audio")
                ?.optString("data")
        }.getOrNull()
        if (b64.isNullOrBlank()) throw IllegalStateException("返回里没找到音频：" + raw.take(200))
        return Base64.decode(b64, Base64.DEFAULT)
    }

    private fun openAiSpeech(text: String, voice: String, style: String): ByteArray {
        val vendor = TtsConfig.vendor
        val url = TtsConfig.baseUrl.trimEnd('/') + vendor.speechPath
        val body = JSONObject()
            .put("model", TtsConfig.useModel)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", TtsConfig.format)
            .put("speed", TtsConfig.speed.toDouble())
        if (style.isNotBlank()) body.put("instructions", style)

        val conn = open(url, "POST")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            throw IllegalStateException("TTS HTTP " + code + "：" + err.take(200))
        }
        val bytes = conn.inputStream.use { input ->
            ByteArrayOutputStream().also { out -> input.copyTo(out, 64 * 1024) }.toByteArray()
        }
        conn.disconnect()
        if (bytes.isEmpty()) throw IllegalStateException("返回了空音频")
        return bytes
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_CONNECT
            readTimeout = TIMEOUT_READ
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Nakour")
        }
        val key = TtsConfig.apiKey
        if (key.isNotBlank()) {
            // 小米用 api-key，OpenAI 系用 Authorization —— 两个都带上，各家认哪个都行
            conn.setRequestProperty("Authorization", "Bearer " + key)
            conn.setRequestProperty("api-key", key)
        }
        return conn
    }
}
