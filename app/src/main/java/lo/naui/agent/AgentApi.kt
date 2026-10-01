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

    /* ================= 带工具的调用 ================= */

    /** 一次调用的用量（各家都按 OpenAI 的 usage 字段给） */
    data class Usage(
        /** 输入里命中缓存的 token（有的家给，没有就是 0） */
        val cachedTokens: Int = 0,
        /** 输入里没命中缓存的 token */
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        /** 这次请求花了多久 */
        val millis: Long = 0,
    ) {
        val totalIn: Int get() = cachedTokens + inputTokens
    }

    /** 模型回的一条：要么是话，要么是要调工具 */
    data class Reply(
        val text: String,
        val toolCalls: List<Call>,
        val usage: Usage = Usage(),
    )

    data class Call(val id: String, val name: String, val args: JSONObject)

    /**
     * 直接把 messages 数组发出去（工具循环里要拼 tool_calls / tool 这些角色，
     * 用 [ChatMessage] 那种简单模型表达不了）。
     */
    suspend fun raw(
        system: String,
        messages: JSONArray,
        tools: List<AgentTool>,
        temperature: Float,
        maxTokens: Int,
    ): Reply = withContext(Dispatchers.IO) {
        val url = URL(endpoint(AgentStore.baseUrl, "chat/completions"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + AgentStore.apiKey)
        }

        val root = JSONObject()
        root.put("model", AgentStore.model)
        root.put("temperature", temperature.toDouble())
        if (maxTokens > 0) root.put("max_tokens", maxTokens)
        root.put("stream", false)

        val list = JSONArray()
        if (system.isNotBlank()) list.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until messages.length()) list.put(messages.get(i))
        root.put("messages", list)

        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { a -> tools.forEach { a.put(it.toJson()) } })
            root.put("tool_choice", "auto")
        }

        runCatching {
            conn.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        }.getOrElse {
            conn.disconnect()
            throw IllegalStateException("连不上 " + url.host + "：" + (it.message ?: ""))
        }

        val startedAt = System.currentTimeMillis()
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val elapsed = System.currentTimeMillis() - startedAt
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException("HTTP " + code + "：" + (msg?.takeIf { it.isNotBlank() } ?: body.take(300)))
        }

        val json = JSONObject(body)
        val msg = runCatching {
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        }.getOrNull() ?: throw IllegalStateException("返回里没有 message")

        // 用量：prompt_tokens_details.cached_tokens 是命中缓存那部分
        val usage = runCatching {
            val u = json.optJSONObject("usage")
            val prompt = u?.optInt("prompt_tokens", 0) ?: 0
            val cached = u?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0)
                ?: u?.optInt("prompt_cache_hit_tokens", 0)
                ?: 0
            Usage(
                cachedTokens = cached,
                inputTokens = (prompt - cached).coerceAtLeast(0),
                outputTokens = u?.optInt("completion_tokens", 0) ?: 0,
                millis = elapsed,
            )
        }.getOrDefault(Usage(millis = elapsed))

        val text = msg.optString("content", "")
        val calls = mutableListOf<Call>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fn = o.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                if (name.isBlank()) continue
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw) }.getOrElse { JSONObject() }
                calls += Call(o.optString("id", "call_" + i), name, args)
            }
        }

        Reply(text, calls, usage)
    }

    /** 问服务商有哪些模型（GET /v1/models） */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(endpoint(AgentStore.baseUrl, "models"))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("Authorization", "Bearer " + AgentStore.apiKey)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) throw IllegalStateException("HTTP " + code + "：" + body.take(160))

            val out = mutableListOf<String>()
            runCatching {
                JSONObject(body).optJSONArray("data")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id", "") ?: continue
                        if (id.isNotBlank()) out += id
                    }
                }
            }
            if (out.isEmpty()) {
                runCatching {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
                    }
                }
            }
            if (out.isEmpty()) throw IllegalStateException("这个接口没返回模型列表")
            out.distinct().sorted()
        }
    }

    /** 把工具跑出来的东西包成一条 tool 消息 */
    fun toolMessage(id: String, name: String, content: String): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("name", name)
            .put("content", content.take(20_000))

    /** 模型的"我要调工具"那条，得原样塞回对话里 */
    fun assistantToolMessage(calls: List<Call>): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", JSONObject.NULL)
        .put(
            "tool_calls",
            JSONArray().also { arr ->
                calls.forEach { c ->
                    arr.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", c.name).put("arguments", c.args.toString())
                            )
                    )
                }
            }
        )

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
        val sys = AgentStore.systemPromptNow
        if (sys.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", sys))
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
