package lo.naui.book

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 书的本地存储。
 *
 * 跟对话一样：一本书一个 JSON 文件，放在 files/books/ 下。
 * 正文本来就是纯文本，塞 JSON 里也不大。
 */
object BookStore {

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "books")
        if (!d.exists()) d.mkdirs()
        dir = d
    }

    /** 朗读音频放这儿 */
    fun audioDir(ctx: Context): File =
        File(ctx.filesDir, "books_audio").apply { mkdirs() }

    fun newId(): String = System.currentTimeMillis().toString()

    fun list(): List<Book> {
        val d = dir ?: return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { Book.from(JSONObject(f.readText())) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Book? {
        val f = file(id) ?: return null
        if (!f.exists()) return null
        return runCatching { Book.from(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(book: Book) {
        val f = file(book.id) ?: return
        runCatching { f.writeText(book.toJson().toString()) }
    }

    fun delete(ctx: Context, id: String) {
        runCatching { file(id)?.delete() }
        // 顺手把它的朗读文件也清了
        runCatching {
            audioDir(ctx).listFiles { f -> f.name.startsWith(id + "_") }?.forEach { it.delete() }
        }
    }

    private fun file(id: String): File? = dir?.let { File(it, id + ".json") }
}
