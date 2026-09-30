package lo.naui.book

import org.json.JSONArray
import org.json.JSONObject

/** 书里的一个人物：名字 + 角色（女主 / 男主 / 配角 …） */
data class BookCharacter(
    val name: String,
    val role: String,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("role", role)

    companion object {
        fun from(o: JSONObject?) = BookCharacter(
            name = o?.optString("name", "").orEmpty(),
            role = o?.optString("role", "").orEmpty(),
        )
    }
}

/**
 * 一章。
 *
 * [notes] 是**与 AI 的对话记录**（"插入"按钮进来的那些），单独存着 ——
 * 用户要求正文里不许出现书外内容，所以纠错的话不能写进 [content]。
 */
data class BookChapter(
    val index: Int,
    val title: String,
    val content: String,
    val notes: List<String> = emptyList(),
    /** 分角色合成后合并好的那一整段（本机文件路径） */
    val audioPath: String = "",
    /** 分角色生成的零碎片段，留着方便重合成时复用 */
    val audioParts: List<String> = emptyList(),
) {
    val hasAudio: Boolean get() = audioPath.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("title", title)
        .put("content", content)
        .put("notes", JSONArray(notes))
        .put("audioPath", audioPath)
        .put("audioParts", JSONArray(audioParts))

    companion object {
        fun from(o: JSONObject?): BookChapter? {
            if (o == null) return null
            val notes = mutableListOf<String>()
            o.optJSONArray("notes")?.let { for (i in 0 until it.length()) notes += it.optString(i) }
            val parts = mutableListOf<String>()
            o.optJSONArray("audioParts")?.let { for (i in 0 until it.length()) parts += it.optString(i) }
            return BookChapter(
                index = o.optInt("index", 0),
                title = o.optString("title", ""),
                content = o.optString("content", ""),
                notes = notes,
                audioPath = o.optString("audioPath", ""),
                audioParts = parts,
            )
        }
    }
}

/** 一本书 */
data class Book(
    val id: String,
    val title: String,
    /** 生成开头时填的「初始词」 */
    val seed: String,
    val characters: List<BookCharacter>,
    val chapters: List<BookChapter>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("seed", seed)
        .put("characters", JSONArray().also { a -> characters.forEach { a.put(it.toJson()) } })
        .put("chapters", JSONArray().also { a -> chapters.forEach { a.put(it.toJson()) } })
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun from(o: JSONObject): Book? {
            val id = o.optString("id", "")
            if (id.isBlank()) return null
            val chars = mutableListOf<BookCharacter>()
            o.optJSONArray("characters")?.let {
                for (i in 0 until it.length()) chars += BookCharacter.from(it.optJSONObject(i))
            }
            val chapters = mutableListOf<BookChapter>()
            o.optJSONArray("chapters")?.let {
                for (i in 0 until it.length()) BookChapter.from(it.optJSONObject(i))?.let { c -> chapters += c }
            }
            return Book(
                id = id,
                title = o.optString("title", "没名字的书"),
                seed = o.optString("seed", ""),
                characters = chars,
                chapters = chapters,
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }
}
