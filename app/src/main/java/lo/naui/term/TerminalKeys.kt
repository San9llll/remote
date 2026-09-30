package lo.naui.term

/** 特殊键 → 终端序列。光标键要看程序有没有开 DECCKM。 */
object TerminalKeys {

    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007f"

    fun up(app: Boolean) = if (app) "\u001bOA" else "\u001b[A"
    fun down(app: Boolean) = if (app) "\u001bOB" else "\u001b[B"
    fun right(app: Boolean) = if (app) "\u001bOC" else "\u001b[C"
    fun left(app: Boolean) = if (app) "\u001bOD" else "\u001b[D"

    const val HOME = "\u001b[H"
    const val END = "\u001b[F"
    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"
    const val INSERT = "\u001b[2~"
    const val DELETE = "\u001b[3~"
    const val F1 = "\u001bOP"
    const val F2 = "\u001bOQ"
    const val F3 = "\u001bOR"
    const val F4 = "\u001bOS"
    const val F5 = "\u001b[15~"
    const val F6 = "\u001b[17~"

    /** Ctrl + 一个字母 → 控制码 */
    fun ctrl(ch: Char): String {
        val c = ch.uppercaseChar()
        return when {
            c in 'A'..'Z' -> ((c.code - 'A'.code + 1).toChar()).toString()
            ch == ' ' -> "\u0000"
            ch == '[' -> "\u001b"
            ch == '\\' -> "\u001c"
            ch == ']' -> "\u001d"
            ch == '^' -> "\u001e"
            ch == '_' -> "\u001f"
            ch == '?' -> "\u007f"
            else -> ch.toString()
        }
    }
}
