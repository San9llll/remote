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
    /** 思考内容（R1 / o1 这类才有），存下来是为了**退出再进来还能点开看** */
    val reasoning: String = "",
    /** 想了几次 */
    val thinkRounds: Int = 0,
    /** 工具记录（脱敏过的文本行） */
    val toolLog: List<String> = emptyList(),
    /** 结构化工具记录：name|label|brief|ok|chars|millis（用 | 拼，省得再引一层 JSON） */
    val toolSteps: List<String> = emptyList(),
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
 *
 * ⚠️ **成功路径上不要调 `conn.disconnect()`**（1.00.0 踩的）：
 * 正文读完之后不 disconnect，socket 会回到 JDK 的 keep-alive 缓存里，
 * 下一条请求就能复用，省掉一次 DNS + TCP + TLS 握手。
 * 工具循环一轮一个请求（默认最多 8 轮），原来每轮都重新握手 ——
 * 移动网络上一次握手两三百毫秒，全花在等首字上。
 * 只有**没把正文读完**的错误路径才需要手动 disconnect。
 */
object AgentApi {

    private const val TIMEOUT_MS = 60_000

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
        // ⚠️ 这儿**故意不 disconnect**：正文已经读干净了，socket 会进 JDK 的
        // keep-alive 缓存，下一条请求直接复用 —— 省掉一次 DNS + TCP + TLS 握手。
        // 工具循环一轮一个请求，最多 8 轮，原来每轮都重新握手（首字延迟里
        // 移动网络上一次握手就是两三百毫秒）。只有出错那条路才需要 disconnect。

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
        // ⚠️ 这儿**故意不 disconnect**：正文已经读干净了，socket 会进 JDK 的
        // keep-alive 缓存，下一条请求直接复用 —— 省掉一次 DNS + TCP + TLS 握手。
        // 工具循环一轮一个请求，最多 8 轮，原来每轮都重新握手（首字延迟里
        // 移动网络上一次握手就是两三百毫秒）。只有出错那条路才需要 disconnect。

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

    /* ================= 流式（边想边说） ================= */

    /**
     * 流式请求。
     *
     * 要让界面**边思考边显示**，就不能等整个响应回来。
     * 这里走 SSE：一行一行吃 `data: {...}`，把增量的
     * 正文 / 思考内容 / 工具调用分别吐给回调。
     *
     * 三家字段名不一样，都试一遍：
     *   - `reasoning_content`  DeepSeek R1
     *   - `reasoning`          OpenAI 系
     *   - `thinking`           有些中转站
     */
    suspend fun stream(
        system: String,
        messages: JSONArray,
        tools: List<AgentTool>,
        temperature: Float,
        maxTokens: Int,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): Reply = withContext(Dispatchers.IO) {
        val url = URL(endpoint(AgentStore.baseUrl, "chat/completions"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + AgentStore.apiKey)
            setRequestProperty("Accept", "text/event-stream")
        }

        val root = JSONObject()
        root.put("model", AgentStore.model)
        root.put("temperature", temperature.toDouble())
        // 0 或不传 = 不限。有些家对超大值会直接报错，所以干脆不带这个字段
        if (maxTokens > 0) root.put("max_tokens", maxTokens)
        root.put("stream", true)

        val list = JSONArray()
        if (system.isNotBlank()) list.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until messages.length()) list.put(messages.get(i))
        root.put("messages", list)

        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { a -> tools.forEach { a.put(it.toJson()) } })
            root.put("tool_choice", "auto")
        }

        val startedAt = System.currentTimeMillis()
        runCatching {
            conn.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        }.getOrElse {
            conn.disconnect()
            throw IllegalStateException("连不上 " + url.host + "：" + (it.message ?: ""))
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException("HTTP " + code + "：" + (msg?.takeIf { it.isNotBlank() } ?: err.take(300)))
        }

        val text = StringBuilder()
        val reason = StringBuilder()
        // 流式里的 tool_calls 是按 index 增量来的，得自己拼
        val callBuf = LinkedHashMap<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
        var cached = 0
        var input = 0
        var output = 0

        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue

                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue

                chunk.optJSONObject("usage")?.let { u ->
                    val prompt = u.optInt("prompt_tokens", 0)
                    cached = u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0)
                        ?: u.optInt("prompt_cache_hit_tokens", 0)
                    input = (prompt - cached).coerceAtLeast(0)
                    output = u.optInt("completion_tokens", 0)
                }

                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    ?: continue

                // 思考内容：三家字段名不一样，都看一眼。
                //
                // ⚠️ 不能用 `optString(key).ifBlank { ... }` —— JSON 里那个字段是
                // `null` 的时候，optString 返回的是**字符串 "null"**，isNotBlank 为真，
                // 于是界面上会一直往外吐 "null"。所以得自己判 isNull。
                val r = delta.strOrNull("reasoning_content")
                    ?: delta.strOrNull("reasoning")
                    ?: delta.strOrNull("thinking")
                if (!r.isNullOrBlank()) {
                    reason.append(r)
                    onReasoning(r)
                }

                val c = delta.strOrNull("content")
                if (!c.isNullOrBlank()) {
                    text.append(c)
                    onDelta(c)
                }

                delta.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val idx = o.optInt("index", 0)
                        val buf = callBuf.getOrPut(idx) {
                            Triple(StringBuilder(), StringBuilder(), StringBuilder())
                        }
                        o.optString("id").takeIf { it.isNotBlank() }?.let { buf.first.append(it) }
                        o.optJSONObject("function")?.let { f ->
                            f.optString("name").takeIf { it.isNotBlank() }?.let { buf.second.append(it) }
                            f.optString("arguments").takeIf { it.isNotBlank() }?.let { buf.third.append(it) }
                        }
                    }
                }
            }
        }
        // 同样不 disconnect：SSE 已经读到 EOF，连接可以留给下一轮复用

        val calls = callBuf.entries.sortedBy { it.key }.mapNotNull { (i, buf) ->
            val name = buf.second.toString().trim()
            if (name.isBlank()) return@mapNotNull null
            val argsRaw = buf.third.toString().ifBlank { "{}" }
            Call(
                id = buf.first.toString().ifBlank { "call_" + i },
                name = name,
                args = runCatching { JSONObject(argsRaw) }.getOrElse { JSONObject() },
            )
        }

        Reply(
            text = text.toString(),
            toolCalls = calls,
            usage = Usage(cached, input, output, System.currentTimeMillis() - startedAt),
            reasoning = reason.toString(),
        )
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
        /** 思考内容（R1 / o1 这类会给 reasoning_content） */
        val reasoning: String = "",
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
        // 不 disconnect，留给下一条请求复用（见上面 complete 那段注释）
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
            if (code !in 200..299) {
                conn.disconnect()      // 只有这条路没把正文用完，得手动收
                throw IllegalStateException("HTTP " + code + "：" + body.take(160))
            }

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

    /**
     * 安全地取一个字符串字段。
     *
     * `null` / 缺失 / 空串 / 字面量 "null" 一律当没有 ——
     * 后面这个最坑，不少中转站会真的把字符串 "null" 塞进 content 或 reasoning 里。
     */
    private fun JSONObject.strOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        if (v.isBlank()) return null
        if (v == "null" || v == "NULL") return null
        return v
    }

    /** 把工具跑出来的东西包成一条 tool 消息 */
    fun toolMessage(id: String, name: String, content: String): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("name", name)
            // 用 AgentContext.clampToolResult 而不是简单 take ——
            // 它是照 astrbot 那套做的：**留头留尾**。
            // 头是命令、尾是报错，中间一大坨通常没用；
            // 直接截掉尾巴会把最关键的报错信息切没。
            .put("content", AgentContext.clampToolResult(content))

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
