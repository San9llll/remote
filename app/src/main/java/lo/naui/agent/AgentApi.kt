package lo.naui.agent

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条消息。
 *
 * images 是发送时用的 data:image/...;base64,xxx；
 * imageCount 是**落盘用**的 —— 存本地时只记几张，不存 base64。
 */
data class ChatMessage(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
    val imageCount: Int = images.size,
)

/** 附件：图片走 base64 塞进 content，文本文件直接拼到正文前面 */
data class ChatAttachment(
    val name: String,
    val isImage: Boolean,
    val dataUrl: String = "",
    val text: String = "",
    val bytes: Long = 0L,
)

/**
 * OpenAI 兼容的 chat/completions。
 *
 * 不用 okhttp —— 这里只有「发一个 JSON、收一个 JSON」，
 * HttpURLConnection 是 JDK 自带的，少一个依赖少一份风险。
 */
object AgentApi {

    private const val TIMEOUT_MS = 120_000

    fun endpoint(base: String, path: String): String {
        var b = base.trim().trimEnd('/')
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        return b + "/" + path
    }

    suspend fun complete(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val url = URL(endpoint(AgentStore.baseUrl, "chat/completions"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + AgentStore.apiKey)
            setRequestProperty("Accept", "application/json")
        }

        runCatching {
            conn.outputStream.use { it.write(buildBody(history).toByteArray(Charsets.UTF_8)) }
        }.getOrElse {
            conn.disconnect()
            throw IllegalStateException("连不上 " + url.host + "：" + (it.message ?: it.javaClass.simpleName))
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull()
            throw IllegalStateException("HTTP " + code + "：" + (msg?.takeIf { it.isNotBlank() } ?: body.take(300)))
        }

        val text = runCatching {
            JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
        }.getOrNull().orEmpty()

        text.ifBlank { throw IllegalStateException("返回里没有内容：" + body.take(300)) }
    }

    /**
     * 通用入口：自己给 baseUrl / key / model / 系统提示词。
     *
     * 书柜要用"你是小说家"这类提示词，不能吃 Agent 页那套人格，
     * 所以单独开一个口子，配置还是从 AgentStore 取（用户只填一份）。
     */
    suspend fun completeWith(
        system: String,
        history: List<ChatMessage>,
        temperature: Float = 0.85f,
        maxTokens: Int = 4096,
        baseUrl: String = AgentStore.baseUrl,
        apiKey: String = AgentStore.apiKey,
        model: String = AgentStore.model,
    ): String = withContext(Dispatchers.IO) {
        val url = URL(endpoint(baseUrl, "chat/completions"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + apiKey)
            setRequestProperty("Accept", "application/json")
        }

        runCatching {
            conn.outputStream.use { it.write(buildBody(system, history, temperature, maxTokens).toByteArray(Charsets.UTF_8)) }
        }.getOrElse {
            conn.disconnect()
            throw IllegalStateException("连不上 " + url.host + "：" + (it.message ?: ""))
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException("HTTP " + code + "：" + (msg?.takeIf { it.isNotBlank() } ?: body.take(300)))
        }

        val text = runCatching {
            JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
        }.getOrNull().orEmpty()

        text.ifBlank { throw IllegalStateException("返回里没有内容：" + body.take(300)) }
    }

    private fun buildBody(system: String, history: List<ChatMessage>, temperature: Float, maxTokens: Int): String {
        val root = JSONObject()
        root.put("model", AgentStore.model)
        root.put("temperature", temperature.toDouble())
        root.put("max_tokens", maxTokens)
        root.put("stream", false)
        val messages = JSONArray()
        if (system.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        history.forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }
        root.put("messages", messages)
        return root.toString()
    }

    private fun buildBody(history: List<ChatMessage>): String {
        val root = JSONObject()
        root.put("model", AgentStore.model)
        root.put("temperature", AgentStore.temperature.toDouble())
        root.put("max_tokens", AgentStore.maxTokens)
        root.put("stream", false)

        val messages = JSONArray()
        if (AgentStore.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", AgentStore.systemPrompt))
        }
        history.forEach { m ->
            // 历史里从本地读回来的消息没有 base64，只有 imageCount，那部分图就不重发了
            val content: Any = if (m.images.isEmpty()) {
                m.text
            } else {
                JSONArray().apply {
                    if (m.text.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", m.text))
                    }
                    m.images.forEach { data ->
                        put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", data))
                        )
                    }
                }
            }
            messages.put(JSONObject().put("role", m.role).put("content", content))
        }
        root.put("messages", messages)
        return root.toString()
    }
}
