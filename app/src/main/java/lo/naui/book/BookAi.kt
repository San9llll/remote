package lo.naui.book

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lo.naui.agent.AgentApi
import lo.naui.agent.AgentStore
import lo.naui.agent.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/** 一段朗读文本：谁说的 + 说什么 + 用什么语气 */
data class SpeechSegment(
    val speaker: String,
    val text: String,
    val style: String,
)

/**
 * 书柜用的 AI 调用。
 *
 * 配置直接复用 Agent 页那份（baseUrl / key / 模型），
 * 但**系统提示词是自己这一套** —— 写小说和聊天不是一回事。
 */
object BookAi {

    private fun novelSystem(): String = """
你是一位中文小说家。你的任务是写小说正文。

硬性要求：
1. 只输出正文，不要写"好的""下面我来写"这类对话式的话
2. 不要输出 Markdown 标记（不要 #、不要 **、不要 ```）
3. 第三人称叙述，有场景、有动作、有心理
4. 对话用中文引号「」或者“”
5. 文风统一，不要跳戏

""".trim()

    private fun characterBlock(book: Book): String =
        if (book.characters.isEmpty()) {
            "（作者没指定人物）"
        } else {
            book.characters.joinToString("\n") { "- ${it.name}：${it.role}" }
        }

    /* ================= 生成开头 ================= */

    suspend fun generateOpening(book: Book, wordTarget: Int = 1500): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prompt = buildString {
                    append("请写一本书的开头。\n\n")
                    append("【书里的初始设定】\n").append(book.seed.ifBlank { "（作者没写设定，你自由发挥）" }).append("\n\n")
                    append("【人物】\n").append(characterBlock(book)).append("\n\n")
                    append("要求：\n")
                    append("1. 直接进入场景，不要写序言或者概要\n")
                    append("2. 大约 ").append(wordTarget).append(" 字\n")
                    append("3. 结尾留一个让人想读下去的钩子\n")
                }
                AgentApi.completeWith(novelSystem(), listOf(ChatMessage("user", prompt)))
                    .let { clean(it) }
            }
        }

    /** 让 AI 起个书名（用户没填的时候用） */
    suspend fun suggestTitle(seed: String, characters: List<BookCharacter>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prompt = "根据下面的设定起一个书名，只回书名本身，不要任何解释、不要书名号：\n" +
                    "设定：" + seed + "\n" +
                    "人物：" + characters.joinToString("、") { it.name }
                clean(AgentApi.completeWith(
                    "你是一个会起书名的编辑。只回一个书名，不超过 12 个字。",
                    listOf(ChatMessage("user", prompt)),
                    temperature = 0.9f,
                    maxTokens = 64,
                )).lineSequence().firstOrNull()?.trim()?.take(20).orEmpty()
            }
        }

    /* ================= 续写 ================= */

    suspend fun generateNext(book: Book, wordTarget: Int = 1500): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val last = book.chapters.lastOrNull()
                val tail = last?.content?.takeLast(1000).orEmpty()
                val prompt = buildString {
                    append("这是《").append(book.title).append("》。已经写到第 ")
                        .append(book.chapters.size).append(" 章。\n\n")
                    append("【初始设定】\n").append(book.seed).append("\n\n")
                    append("【人物】\n").append(characterBlock(book)).append("\n\n")
                    if (tail.isNotBlank()) {
                        append("【上一章结尾】\n").append(tail).append("\n\n")
                    }
                    append("请接着写第 ").append(book.chapters.size + 1).append(" 章。\n")
                    append("要求：\n")
                    append("1. 直接写正文，第一行是章节标题（形如 `第十二章 xxx`）\n")
                    append("2. 大约 ").append(wordTarget).append(" 字\n")
                    append("3. 承接上文，情节往前推进，不要重复前面写过的内容\n")
                }
                clean(AgentApi.completeWith(novelSystem(), listOf(ChatMessage("user", prompt))))
            }
        }

    /* ================= 插入：跟 AI 聊（不写进正文） ================= */

    /**
     * 纠错用。
     *
     * 用户明确要求正文里不能混进书外内容，所以这些对话**只存在 chapter.notes 里**，
     * 永远不会写进 chapter.content。
     */
    suspend fun discuss(book: Book, chapter: BookChapter?, message: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prompt = buildString {
                    append("【书】").append(book.title).append("\n")
                    append("【人物】").append(book.characters.joinToString("、") { it.name + "(" + it.role + ")" }).append("\n")
                    chapter?.let {
                        append("【当前章节】").append(it.title).append("\n")
                        append("【正文】\n").append(it.content.take(6000)).append("\n\n")
                    }
                    append("【作者的问题】\n").append(message).append("\n\n")
                    append("请回答作者。如果发现正文里有前后矛盾、时间线错乱、人设不一致的地方，指出来。")
                    append("注意：你只是在跟我讨论，**不要重写正文**。")
                }
                clean(AgentApi.completeWith(
                    "你是这本书的责任编辑，负责帮作者梳理逻辑、抓前后矛盾。回答要具体、简短。",
                    listOf(ChatMessage("user", prompt)),
                    temperature = 0.4f,
                ))
            }
        }

    /* ================= 分角色（给 TTS 用） ================= */

    /**
     * 把一章正文拆成「旁白 / 各角色」说的段。
     *
     * 拆不出来就整章当旁白 —— 宁可不分角色，也不能把正文弄丢。
     */
    suspend fun segmentsForSpeech(book: Book, chapter: BookChapter): Result<List<SpeechSegment>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val names = book.characters.joinToString("、") { it.name }
                val prompt = buildString {
                    append("把下面的正文按「谁在说这句话」切段。\n\n")
                    append("可用的人物：").append(if (names.isBlank()) "（没有）" else names).append("\n")
                    append("说话人只能填上面的人物名，或者填「旁白」。\n\n")
                    append("严格输出 JSON 数组，不要任何解释、不要代码块：\n")
                    append("""[{"speaker":"旁白","text":"……"},{"speaker":"人物名","text":"……"}]""" + "\n\n")
                    append("规矩：\n")
                    append("1. text 必须是原文原样的一小段，**一个字都不许改、不许删、不许加**\n")
                    append("2. 把原文从头到尾完整切完，段与段首尾相接，不能漏内容\n")
                    append("3. 每段别太长，200 字以内\n\n")
                    append("【正文】\n").append(chapter.content)
                }
                val raw = AgentApi.completeWith(
                    "你是一个文本切分工具，只会输出严格 JSON，不会输出别的话。",
                    listOf(ChatMessage("user", prompt)),
                    temperature = 0.1f,
                    maxTokens = 8192,
                )
                parseSegments(raw, book, chapter)
            }
        }

    private fun parseSegments(raw: String, book: Book, chapter: BookChapter): List<SpeechSegment> {
        val json = raw.substringAfter('[', "").let { if (it.isEmpty()) raw else "[" + it }
        val start = json.indexOf('[')
        val end = json.lastIndexOf(']')
        if (start < 0 || end <= start) return listOf(SpeechSegment(TtsConfig.NARRATOR, chapter.content, styleOf(TtsConfig.NARRATOR)))

        val arr = runCatching { JSONArray(json.substring(start, end + 1)) }.getOrNull()
            ?: return listOf(SpeechSegment(TtsConfig.NARRATOR, chapter.content, styleOf(TtsConfig.NARRATOR)))

        val validNames = book.characters.map { it.name }.toSet() + TtsConfig.NARRATOR
        val out = mutableListOf<SpeechSegment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sp = o.optString("speaker", TtsConfig.NARRATOR).trim()
            val tx = o.optString("text", "")
            if (tx.isBlank()) continue
            val speaker = if (sp in validNames) sp else TtsConfig.NARRATOR
            out += SpeechSegment(speaker, tx, styleOf(speaker))
        }
        // 切不出来就整章当旁白，别把正文丢了
        return out.ifEmpty { listOf(SpeechSegment(TtsConfig.NARRATOR, chapter.content, styleOf(TtsConfig.NARRATOR))) }
    }

    fun styleOf(speaker: String): String = when {
        speaker == TtsConfig.NARRATOR -> "用平静、清晰的旁白语气朗读，语速平稳"
        else -> "你是「" + speaker + "」，用符合这个角色性格的语气朗读，自然一点"
    }

    /* ================= 小工具 ================= */

    /** 把 AI 偶尔吐出来的 Markdown 痕迹擦掉 */
    private fun clean(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").let { if (it.startsWith("markdown")) it.removePrefix("markdown") else it }
            t = t.removeSuffix("```").trim()
        }
        return t
    }
}
