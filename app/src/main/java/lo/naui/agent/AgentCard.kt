package lo.naui.agent

/**
 *卡片里的一个块。
 *
 *用户的要求：「思考过程、回复、执行命令按顺序排，不要堆在一起」。
 *所以一条回复不再是一坨字符串，而是**按发生顺序**排好的块：
 *
 * THINK →模型这一步想了什么（界面上只露一行浅色小字）
 * TEXT →它跟用户说的话（正常字号）
 * TOOL →它干的一件事（一行小字：角标 +名字 +耗时 +目录）
 *
 *流式期间增量的思考和正文会并到**最后一块**上（同 kind就追加），
 *所以看着是「这一行在长」，而不是每来一个字就多一行。
 */
data class CardEvent(
 /** think / text / tool */
 val kind: String,
 /** think和 text用：内容 */
 val text: String = "",
 /** tool用：人话名字（Shell Command这种） */
 val label: String = "",
 /** tool用：具体要执行的东西（命令原文 /路径），点开角标才看 */
 val detail: String = "",
 /** tool用：这条要操作的目录 */
 val dir: String = "",
 /** tool用：花了多久 */
 val millis: Long =0L,
 val ok: Boolean = true,
 val brief: String = "",
 val chars: Int =0,
) {
 /** 「00.1s」——用户点名要的一位数格式 */
 fun cost(): String =
 if (millis <0L) "" else String.format(java.util.Locale.US, "%.1fs", millis /1000.0)

 companion object {
 const val THINK = "think"
 const val TEXT = "text"
 const val TOOL = "tool"

 /**
 *落盘用的分隔符。
 *
 * ⚠️用 US（0x1F）而不是竖线 ——命令原文里带 `|`太常见了。
 *1.02.0的 toolLog就是拿竖线拼的，拆出来字段全串味。
 */
 private const val SEP = "\u001F"

 fun encode(e: CardEvent): String = listOf(
 e.kind, e.text, e.label, e.detail, e.dir,
 e.millis.toString(), if (e.ok) "1" else "0", e.chars.toString(), e.brief,
 ).joinToString(SEP)

 fun decode(raw: String): CardEvent? {
 //老格式（1.02 /1.03存下来的）：label|brief|ok|chars|millis
 if (!raw.contains(SEP)) {
 val p = raw.split("|")
 if (p.size <5) return null
 return CardEvent(
 kind = TOOL,
 label = p[0],
 brief = p.getOrElse(1) { "" },
 ok = p[2].toBoolean(),
 chars = p[3].toIntOrNull() ?:0,
 millis = p[4].toLongOrNull() ?:0L,
 )
 }
 val p = raw.split(SEP)
 if (p.size <9) return null
 return CardEvent(
 kind = p[0],
 text = p[1],
 label = p[2],
 detail = p[3],
 dir = p[4],
 millis = p[5].toLongOrNull() ?:0L,
 ok = p[6] == "1",
 chars = p[7].toIntOrNull() ?:0,
 brief = p[8],
 )
 }

 fun decodeAll(list: List<String>): List<CardEvent> = list.mapNotNull { decode(it) }

 fun encodeAll(list: List<CardEvent>): List<String> = list.map { encode(it) }
 }
}

/**工具的内部名字 →那一行左边显示的名字 */
fun toolLabel(name: String): String = when (name) {
 AgentTools.SHELL -> "Shell Command"
 AgentTools.BATCH -> "Batch Shell"
 AgentTools.READ -> "Read File"
 AgentTools.READ_MANY -> "Read Files"
 AgentTools.WRITE -> "Write File"
 AgentTools.LIST -> "List Dir"
 AgentTools.FIND -> "Find Files"
 AgentTools.GREP -> "Grep Text"
 AgentTools.DEVICE -> "Device Info"
 AgentTools.DOWNLOAD -> "Download"
 AgentTools.DOWNLOAD_CHECK -> "Check Download"
 AgentTools.FILE_OP -> "File Op"
 AgentTools.DELETE -> "Delete Path"
 AgentTools.TREE -> "Tree"
 AgentTools.STAT -> "Stat"
 AgentTools.ARCHIVE -> "Archive"
 AgentTools.APPS -> "List Apps"
 AgentTools.OPEN -> "Open App"
 AgentTools.CLIPBOARD -> "Clipboard"
 AgentTools.NOTICE -> "Notify"
 AgentTools.SHOT -> "Screenshot"
 else -> name
}

/**
 *把一段增量并到最后一块上（同类型就追加，不同类型新开一块）。
 *
 *跟 [AgentTaskStore.setEvents]配着用：每并完一次就把整份列表换掉。
 */
fun pushEvents(list: MutableList<CardEvent>, kind: String, delta: String) {
 if (delta.isEmpty()) return
 val last = list.lastOrNull()
 if (last != null && last.kind == kind) {
 list[list.size -1] = last.copy(text = last.text + delta)
 } else {
 list += CardEvent(kind = kind, text = delta)
 }
}

/**点开角标时展开的那段「具体要执行的东西」 */
fun opDetail(name: String, args: org.json.JSONObject): String = when (name) {
 AgentTools.SHELL -> args.optString("command", "").trim()
 AgentTools.BATCH -> {
 val arr = args.optJSONArray("commands")
 val sb = StringBuilder()
 val n = if (arr == null) 0 else arr.length()
 var i =0
 while (i < n) {
 if (sb.isNotEmpty()) sb.append("\n")
 sb.append(arr!!.optString(i))
 i++
 }
 sb.toString()
 }
 AgentTools.WRITE -> args.optString("content", "").take(300)
 AgentTools.READ -> args.optString("path", "").trim()
 AgentTools.DELETE -> args.optString("path", "").trim()
 AgentTools.FILE_OP -> args.optString("action", "") + " " +
 args.optString("src", "") + " → " + args.optString("dst", "")
 else -> ""
}

/**这条工具要操作的目录 ——那行最右边显示的 */
fun opDir(name: String, args: org.json.JSONObject): String {
 val keys = when (name) {
 AgentTools.SHELL, AgentTools.BATCH -> listOf("command")
 AgentTools.READ_MANY -> listOf("paths", "path")
 AgentTools.FIND, AgentTools.GREP -> listOf("root", "path")
 AgentTools.FILE_OP -> listOf("src", "dst")
 else -> listOf("path", "target", "root", "src", "mount")
 }
 for (k in keys) {
 val raw = args.optString(k, "")
 if (raw.isBlank()) continue
 val p = firstAbsPath(raw)
 if (p.isNotBlank()) return p
 }
 return ""
}

/**从一段文本里扒出第一个绝对路径 */
private fun firstAbsPath(s: String): String {
 var i =0
 while (i < s.length) {
 if (s[i] == '/') {
 var j = i
 while (j < s.length &&
 s[j] != ' ' && s[j] != '\t' && s[j] != '\n' && s[j] != '"' &&
 s[j] != '\'' && s[j] != ';' && s[j] != '|' && s[j] != '&' &&
 s[j] != ')' && s[j] != '>' && s[j] != ',' && s[j] != '='
 ) j++
 val p = s.substring(i, j)
 if (p.length >1) return p
 }
 i++
 }
 return ""
}
