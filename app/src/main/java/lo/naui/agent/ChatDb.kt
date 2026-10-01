package lo.naui.agent

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一个本地会话 */
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

/**
 * 对话存本地。
 *
 * 一个会话一个 JSON 文件，放在 filesDir/conversations/ 下 ——
 * 不上数据库：会话本来就不多，读一个会话就是读一个文件，够直接。
 *
 * 图片的 base64 **不落盘**，只记「这条有几张图」，
 * 不然聊几句就能把存储撑爆。
 */
object ChatDb {

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "conversations")
        if (!d.exists()) d.mkdirs()
        dir = d
    }

    fun newId(): String = System.currentTimeMillis().toString()

    fun list(): List<Conversation> {
        val d = dir ?: return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Conversation(
                    id = f.name.removeSuffix(".json"),
                    title = o.optString("title", "新对话"),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): List<ChatMessage> {
        val f = file(id) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(
                    role = o.optString("role", "user"),
                    text = o.optString("text", ""),
                    imageCount = o.optInt("images", 0),
                    reasoning = o.optString("reasoning", ""),
                    thinkRounds = o.optInt("think_rounds", 0),
                    toolLog = o.optJSONArray("tool_log")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    }.orEmpty(),
                    toolSteps = o.optJSONArray("tool_steps")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(id: String, title: String, messages: List<ChatMessage>) {
        val f = file(id) ?: return
        runCatching {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("text", m.text)
                        .put("images", m.images.size)
                        .put("reasoning", m.reasoning)
                        .put("think_rounds", m.thinkRounds)
                        .put("tool_log", JSONArray().also { a -> m.toolLog.forEach { a.put(it) } })
                        .put("tool_steps", JSONArray().also { a -> m.toolSteps.forEach { a.put(it) } })
                )
            }
            val root = JSONObject()
                .put("title", title)
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", arr)
            f.writeText(root.toString())
        }
    }

    fun delete(id: String) {
        runCatching { file(id)?.delete() }
    }

    private fun file(id: String): File? = dir?.let { File(it, id + ".json") }

    /** 用第一条用户消息当标题 */
    fun titleOf(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" && it.text.isNotBlank() } ?: return "新对话"
        val t = first.text.replace('\n', ' ').trim()
        return if (t.length > 14) t.take(14) + "…" else t
    }
}
