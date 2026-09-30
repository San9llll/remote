package lo.naui.term

/** 一行。字符 + 前景 + 背景 + 属性分开存，绘制时按"同样式连续段"合并画。 */
class TerminalLine(var cols: Int) {
    var text = CharArray(cols) { ' ' }
    var fg = IntArray(cols) { TerminalColor.DEFAULT_FG }
    var bg = IntArray(cols) { TerminalColor.DEFAULT_BG }
    var flags = ByteArray(cols)
    /** 这一行是不是被自动换行"接上"的（回看重排有用，先留着） */
    var wrapped = false

    fun resize(newCols: Int) {
        if (newCols == cols) return
        val t = CharArray(newCols) { ' ' }
        val f = IntArray(newCols) { TerminalColor.DEFAULT_FG }
        val b = IntArray(newCols) { TerminalColor.DEFAULT_BG }
        val fl = ByteArray(newCols)
        val n = minOf(cols, newCols)
        System.arraycopy(text, 0, t, 0, n)
        System.arraycopy(fg, 0, f, 0, n)
        System.arraycopy(bg, 0, b, 0, n)
        System.arraycopy(flags, 0, fl, 0, n)
        text = t; fg = f; bg = b; flags = fl
        cols = newCols
    }

    fun reset(defFg: Int, defBg: Int) {
        java.util.Arrays.fill(text, ' ')
        java.util.Arrays.fill(fg, defFg)
        java.util.Arrays.fill(bg, defBg)
        java.util.Arrays.fill(flags, 0)
        wrapped = false
    }
}

/** 颜色。前 16 个是标准色，后面按 xterm 的 256 色算。 */
object TerminalColor {

    const val DEFAULT_FG = 0xFFE6E6E6.toInt()
    const val DEFAULT_BG = 0xFF101014.toInt()
    const val DEFAULT_CURSOR = 0xFF7FD1FF.toInt()

    /** VS Code 暗色那套 16 色，看着舒服 */
    val BASE16 = intArrayOf(
        0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
        0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
        0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt(),
    )

    /** 256 色表：16 标准 + 216 色立方 + 24 灰阶 */
    private val TABLE: IntArray = IntArray(256).also { t ->
        for (i in 0 until 16) t[i] = BASE16[i]
        var i = 16
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            val rv = if (r == 0) 0 else 55 + r * 40
            val gv = if (g == 0) 0 else 55 + g * 40
            val bv = if (b == 0) 0 else 55 + b * 40
            t[i++] = (0xFF shl 24) or (rv shl 16) or (gv shl 8) or bv
        }
        for (k in 0 until 24) {
            val v = 8 + k * 10
            t[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    fun of(index: Int): Int = TABLE[index and 0xFF]

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

/** 属性的位标记 */
object Attr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val REVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128
}
