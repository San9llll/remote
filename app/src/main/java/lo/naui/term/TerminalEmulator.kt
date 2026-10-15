package lo.naui.term

/**
 * VT100 / xterm 终端模拟器。
 *
 * 自己写了一个状态机来吃 ANSI 转义序列：光标移动、擦除、滚动区、
 * SGR 颜色（16 色 / 256 色 / 真彩）、备用屏幕、自动换行、括号粘贴……
 * vim / top / htop 这些全屏程序能正常跑，靠的就是这套 + 真 PTY。
 *
 * 需要回写给程序的东西（比如"光标在哪"）通过 [respond] 丢回去。
 */
class TerminalEmulator(
    var rows: Int,
    var cols: Int,
    /** 用户选的那套配色（ANSI 0-15 就从这儿取） */
    var palette: IntArray = TerminalColor.BASE16,
    var defaultFg: Int = TerminalColor.DEFAULT_FG,
    var defaultBg: Int = TerminalColor.DEFAULT_BG,
    maxScrollback: Int = 4000,
    private val respond: (String) -> Unit,
) {

    /** 回看最多留多少行 */
    var maxScrollback: Int = maxScrollback
        set(value) {
            field = value.coerceIn(200, 50000)
            while (scrollback.size > field) scrollback.removeFirst()
        }

    /** 256 色里前 16 个用用户的配色，后面用标准表 */
    private fun paletteOf(index: Int): Int {
        val i = index and 0xFF
        return if (i < 16) palette.getOrElse(i) { TerminalColor.BASE16[i] }
        else TerminalColor.EXTENDED[i]
    }

    companion object {
        /** 宽字符（中文那种占两格）的第二格，用这个占位 */
        const val WIDE_TAIL = '\u0000'

        private val URL_REGEX =
            Regex("(?:https?|ftp)://[^\\s\"'<>\\^`{|}]+")

        private const val GROUND = 0
        private const val ESC = 1
        private const val CSI = 2
        private const val OSC = 3
        private const val CHARSET = 4

    }

    /* ---------------- 屏幕 ---------------- */

    var screen: Array<TerminalLine> = Array(rows) { TerminalLine(cols) }
        private set

    /** 换配色时把默认色的格子刷新一遍 */
    fun applyPalette(newPalette: IntArray, fg: Int, bg: Int) {
        palette = newPalette
        defaultFg = fg
        defaultBg = bg
        if (curFg == oldDefaultFg) curFg = fg
        if (curBg == oldDefaultBg) curBg = bg
        oldDefaultFg = fg
        oldDefaultBg = bg
        onChange?.invoke()
    }

    private var oldDefaultFg = defaultFg
    private var oldDefaultBg = defaultBg
    private var altScreen: Array<TerminalLine>? = null
    var usingAltScreen = false
        private set

    /** 滚出去的行，回看用 */
    val scrollback = ArrayDeque<TerminalLine>()

    /* ---------------- 光标 ---------------- */

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set
    private var savedRow = 0
    private var savedCol = 0

    var scrollTop = 0
        private set
    var scrollBottom = rows - 1
        private set

    /* ---------------- 当前样式 ---------------- */

    var curFg = defaultFg
        private set
    var curBg = defaultBg
        private set
    var curFlags = 0
        private set

    /* ---------------- 模式 ---------------- */

    private var autoWrap = true
    private var originMode = false
    private var cursorKeysApp = false
    private var bracketPaste = false
    private var wrapPending = false

    val isCursorKeysApp: Boolean get() = cursorKeysApp
    val isBracketPaste: Boolean get() = bracketPaste

    /** 界面要显示的东西变了 */
    var onChange: (() -> Unit)? = null

    /** 收到 BEL（\a）时叫一声 —— 界面拿它去震动 */
    var onBell: (() -> Unit)? = null

    /* ---------------- 解析状态 ---------------- */

    private var state = GROUND
    private val params = IntArray(32)
    private var paramCount = 0
    private var paramValue = 0
    private var hasParam = false
    private var prefix = ' '
    private val oscBuf = StringBuilder()

    /* ---------------- UTF-8 ---------------- */

    private var utf8Left = 0
    private var utf8Code = 0

    /* ================= 输入 ================= */

    fun append(bytes: ByteArray, len: Int) {
        for (i in 0 until len) {
            val b = bytes[i].toInt() and 0xFF
            if (utf8Left > 0) {
                if (b and 0xC0 == 0x80) {
                    utf8Code = (utf8Code shl 6) or (b and 0x3F)
                    if (--utf8Left == 0) emitChar(utf8Code)
                    continue
                } else {
                    utf8Left = 0
                    emitChar(0xFFFD)
                }
            }
            if (b >= 0x80) {
                when {
                    b and 0xE0 == 0xC0 -> { utf8Code = b and 0x1F; utf8Left = 1 }
                    b and 0xF0 == 0xE0 -> { utf8Code = b and 0x0F; utf8Left = 2 }
                    b and 0xF8 == 0xF0 -> { utf8Code = b and 0x07; utf8Left = 3 }
                    else -> emitChar(0xFFFD)
                }
                continue
            }
            feedByte(b)
        }
        onChange?.invoke()
    }

    private fun feedByte(b: Int) {
        when (state) {
            GROUND -> when (b) {
                0x1B -> state = ESC
                0x0D -> { cursorCol = 0; wrapPending = false }
                0x0A, 0x0B, 0x0C -> lineFeed()
                0x09 -> tab()
                0x08 -> if (cursorCol > 0) { cursorCol--; wrapPending = false }
                0x07 -> onBell?.invoke()
                else -> if (b >= 0x20 && b != 0x7F) emitChar(b)
            }

            ESC -> when (b) {
                '['.code -> { resetParams(); state = CSI }
                ']'.code -> { oscBuf.setLength(0); state = OSC }
                '('.code, ')'.code, '*'.code, '+'.code -> { prefix = b.toChar(); state = CHARSET }
                'D'.code -> lineFeed()
                'E'.code -> { lineFeed(); cursorCol = 0 }
                'M'.code -> reverseLineFeed()
                '7'.code -> { savedRow = cursorRow; savedCol = cursorCol }
                '8'.code -> { cursorRow = savedRow; cursorCol = savedCol; clampCursor() }
                'c'.code -> reset()
                '='.code -> cursorKeysApp = true
                '>'.code -> cursorKeysApp = false
                else -> Unit
            }
            .also { if (state != CSI && state != OSC && state != CHARSET) state = GROUND }

            CSI -> {
                when {
                    b in 0x30..0x3B -> {           // 参数 / 分隔
                        if (b == ';'.code) {
                            pushParam()
                        } else {
                            hasParam = true
                            paramValue = paramValue * 10 + (b - '0'.code)
                        }
                    }
                    b in 0x3C..0x3F -> prefix = b.toChar()   // ? < = >
                    b in 0x20..0x2F -> Unit                  // 中间字节
                    b in 0x40..0x7E -> {                     // 终止符
                        pushParam()
                        handleCsi(b.toChar())
                        state = GROUND
                    }
                    else -> state = GROUND
                }
            }

            OSC -> {
                if (b == 0x07) {
                    handleOsc(oscBuf.toString())
                    state = GROUND
                } else if (b == 0x1B) {
                    // ST = ESC \
                    handleOsc(oscBuf.toString())
                    state = ESC
                } else {
                    if (oscBuf.length < 512) oscBuf.append(b.toChar())
                }
            }

            CHARSET -> state = GROUND
        }
    }

    /* ================= 参数 ================= */

    private fun resetParams() {
        paramCount = 0
        paramValue = 0
        hasParam = false
        prefix = ' '
        for (i in params.indices) params[i] = 0
    }

    private fun pushParam() {
        if (paramCount < params.size) {
            params[paramCount++] = if (hasParam) paramValue else -1
        }
        paramValue = 0
        hasParam = false
    }

    /** 第 i 个参数，缺省时给 def */
    private fun p(i: Int, def: Int): Int {
        if (i >= paramCount) return def
        val v = params[i]
        return if (v <= 0) def else v
    }

    private fun pZero(i: Int, def: Int): Int {
        if (i >= paramCount) return def
        val v = params[i]
        return if (v < 0) def else v
    }

    /* ================= 控制字符 ================= */

    private fun tab() {
        val next = ((cursorCol / 8) + 1) * 8
        cursorCol = if (next >= cols) cols - 1 else next
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseLineFeed() {
        wrapPending = false
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    /* ================= 滚动 ================= */

    private fun scrollUp(count: Int) {
        repeat(count.coerceAtLeast(1).coerceAtMost(rows)) {
            val top = screen[scrollTop]
            // 只有整屏滚动才进历史（备用屏不进）
            if (scrollTop == 0 && !usingAltScreen) {
                val copy = TerminalLine(cols)
                System.arraycopy(top.text, 0, copy.text, 0, cols)
                System.arraycopy(top.fg, 0, copy.fg, 0, cols)
                System.arraycopy(top.bg, 0, copy.bg, 0, cols)
                System.arraycopy(top.flags, 0, copy.flags, 0, cols)
                scrollback.addLast(copy)
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (r in scrollTop until scrollBottom) {
                val src = screen[r + 1]
                val dst = screen[r]
                System.arraycopy(src.text, 0, dst.text, 0, cols)
                System.arraycopy(src.fg, 0, dst.fg, 0, cols)
                System.arraycopy(src.bg, 0, dst.bg, 0, cols)
                System.arraycopy(src.flags, 0, dst.flags, 0, cols)
            }
            screen[scrollBottom].reset(curFg, curBg)
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceAtLeast(1).coerceAtMost(rows)) {
            for (r in scrollBottom downTo scrollTop + 1) {
                val src = screen[r - 1]
                val dst = screen[r]
                System.arraycopy(src.text, 0, dst.text, 0, cols)
                System.arraycopy(src.fg, 0, dst.fg, 0, cols)
                System.arraycopy(src.bg, 0, dst.bg, 0, cols)
                System.arraycopy(src.flags, 0, dst.flags, 0, cols)
            }
            screen[scrollTop].reset(curFg, curBg)
        }
    }

    /* ================= 画字符 ================= */

    private fun emitChar(code: Int) {
        if (state != GROUND) return
        val width = charWidth(code)
        if (width == 0) return                  // 组合字符先不管
        val ch: Char = if (code <= 0xFFFF) code.toChar() else '\uFFFD'

        if (wrapPending) {
            cursorCol = 0
            lineFeed()
            wrapPending = false
        }
        if (cursorCol + width > cols) {
            if (autoWrap) {
                cursorCol = 0
                lineFeed()
            } else {
                cursorCol = cols - width
            }
        }
        setCell(cursorRow, cursorCol, width, ch)
        cursorCol += width
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        }
    }

    private fun setCell(row: Int, col: Int, width: Int, ch: Char) {
        if (row !in 0 until rows) return
        val line = screen[row]
        if (col !in 0 until cols) return
        line.text[col] = ch
        line.fg[col] = curFg
        line.bg[col] = curBg
        line.flags[col] = curFlags.toByte()
        if (width == 2 && col + 1 < cols) {
            line.text[col + 1] = WIDE_TAIL
            line.fg[col + 1] = curFg
            line.bg[col + 1] = curBg
            line.flags[col + 1] = curFlags.toByte()
        }
    }

    /* ================= CSI ================= */

    private fun handleCsi(final: Char) {
        when (final) {
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop); cursorCol = 0 }
            'G', '`' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                val r = p(0, 1) - 1
                val c = p(1, 1) - 1
                cursorRow = if (originMode) (scrollTop + r).coerceIn(scrollTop, scrollBottom) else r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(pZero(0, 0))
            'K' -> eraseLine(pZero(0, 0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'm' -> sgr()
            'r' -> {
                scrollTop = (p(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorRow = if (originMode) scrollTop else 0
                cursorCol = 0
            }
            'h' -> setMode(true)
            'l' -> setMode(false)
            'n' -> when (pZero(0, 0)) {
                5 -> respond("\u001b[0n")
                6 -> {
                    val r = cursorRow + 1
                    val c = cursorCol + 1
                    respond("\u001b[$r;$c" + "R")
                }
            }
            'c' -> respond("\u001b[?6c")           // 我是 VT102
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol; clampCursor() }
        }
        clampCursor()
    }

    private fun clampCursor() {
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun setMode(on: Boolean) {
        val isPrivate = prefix == '?'
        if (isPrivate) {
            when (pZero(0, 0)) {
                1 -> cursorKeysApp = on
                7 -> autoWrap = on
                6 -> originMode = on
                25 -> cursorVisible = on
                47, 1047 -> switchAlt(on)
                1048 -> {
                    if (on) { savedRow = cursorRow; savedCol = cursorCol }
                    else { cursorRow = savedRow; cursorCol = savedCol; clampCursor() }
                }
                1049 -> switchAlt(on)
                2004 -> bracketPaste = on
            }
        }
    }

    private fun switchAlt(on: Boolean) {
        if (on && !usingAltScreen) {
            altScreen = Array(rows) { TerminalLine(cols) }
            for (i in 0 until rows) {
                System.arraycopy(screen[i].text, 0, altScreen!![i].text, 0, cols)
                System.arraycopy(screen[i].fg, 0, altScreen!![i].fg, 0, cols)
                System.arraycopy(screen[i].bg, 0, altScreen!![i].bg, 0, cols)
                System.arraycopy(screen[i].flags, 0, altScreen!![i].flags, 0, cols)
            }
            screen = Array(rows) { TerminalLine(cols) }
            usingAltScreen = true
            cursorRow = 0
            cursorCol = 0
        } else if (!on && usingAltScreen) {
            altScreen?.let { screen = it }
            altScreen = null
            usingAltScreen = false
            cursorRow = 0
            cursorCol = 0
            scrollTop = 0
            scrollBottom = rows - 1
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) screen[r].reset(curFg, curBg)
            }
            1 -> {
                for (r in 0 until cursorRow) screen[r].reset(curFg, curBg)
                eraseLine(1)
            }
            2, 3 -> {
                for (r in 0 until rows) screen[r].reset(curFg, curBg)
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        if (cursorRow !in 0 until rows) return
        val line = screen[cursorRow]
        val from: Int
        val to: Int
        when (mode) {
            0 -> { from = cursorCol; to = cols - 1 }
            1 -> { from = 0; to = cursorCol }
            else -> { from = 0; to = cols - 1 }
        }
        for (c in from..to) {
            if (c !in 0 until cols) continue
            line.text[c] = ' '
            line.fg[c] = curFg
            line.bg[c] = curBg
            line.flags[c] = 0
        }
    }

    private fun eraseChars(count: Int) {
        val line = screen.getOrNull(cursorRow) ?: return
        for (c in cursorCol until minOf(cursorCol + count, cols)) {
            line.text[c] = ' '
            line.fg[c] = curFg
            line.bg[c] = curBg
            line.flags[c] = 0
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val saveTop = scrollTop
        scrollTop = cursorRow
        scrollDown(count)
        scrollTop = saveTop
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val saveTop = scrollTop
        scrollTop = cursorRow
        scrollUp(count)
        scrollTop = saveTop
    }

    private fun insertChars(count: Int) {
        val line = screen.getOrNull(cursorRow) ?: return
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) {
            line.text[c] = line.text[c - n]
            line.fg[c] = line.fg[c - n]
            line.bg[c] = line.bg[c - n]
            line.flags[c] = line.flags[c - n]
        }
        for (c in cursorCol until minOf(cursorCol + n, cols)) {
            line.text[c] = ' '
            line.fg[c] = curFg
            line.bg[c] = curBg
            line.flags[c] = 0
        }
    }

    private fun deleteChars(count: Int) {
        val line = screen.getOrNull(cursorRow) ?: return
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cursorCol until cols - n) {
            line.text[c] = line.text[c + n]
            line.fg[c] = line.fg[c + n]
            line.bg[c] = line.bg[c + n]
            line.flags[c] = line.flags[c + n]
        }
        for (c in cols - n until cols) {
            line.text[c] = ' '
            line.fg[c] = curFg
            line.bg[c] = curBg
            line.flags[c] = 0
        }
    }

    /* ================= SGR ================= */

    private fun sgr() {
        if (paramCount == 0) {
            curFg = defaultFg
            curBg = defaultBg
            curFlags = 0
            return
        }
        var i = 0
        while (i < paramCount) {
            val v = if (params[i] < 0) 0 else params[i]
            when {
                v == 0 -> { curFg = defaultFg; curBg = defaultBg; curFlags = 0 }
                v == 1 -> curFlags = curFlags or Attr.BOLD
                v == 2 -> curFlags = curFlags or Attr.DIM
                v == 3 -> curFlags = curFlags or Attr.ITALIC
                v == 4 -> curFlags = curFlags or Attr.UNDERLINE
                v == 5 -> curFlags = curFlags or Attr.BLINK
                v == 7 -> curFlags = curFlags or Attr.REVERSE
                v == 8 -> curFlags = curFlags or Attr.HIDDEN
                v == 9 -> curFlags = curFlags or Attr.STRIKE
                v == 21 || v == 22 -> curFlags = curFlags and Attr.BOLD.inv() and Attr.DIM.inv()
                v == 23 -> curFlags = curFlags and Attr.ITALIC.inv()
                v == 24 -> curFlags = curFlags and Attr.UNDERLINE.inv()
                v == 25 -> curFlags = curFlags and Attr.BLINK.inv()
                v == 27 -> curFlags = curFlags and Attr.REVERSE.inv()
                v == 28 -> curFlags = curFlags and Attr.HIDDEN.inv()
                v == 29 -> curFlags = curFlags and Attr.STRIKE.inv()
                v in 30..37 -> curFg = paletteOf(v - 30)
                v == 39 -> curFg = defaultFg
                v in 40..47 -> curBg = paletteOf(v - 40)
                v == 49 -> curBg = defaultBg
                v in 90..97 -> curFg = paletteOf(v - 90 + 8)
                v in 100..107 -> curBg = paletteOf(v - 100 + 8)
                v == 38 || v == 48 -> {
                    val isFg = v == 38
                    val kind = if (i + 1 < paramCount && params[i + 1] >= 0) params[i + 1] else 0
                    if (kind == 5 && i + 2 < paramCount) {
                        val idx = if (params[i + 2] < 0) 0 else params[i + 2]
                        if (isFg) curFg = paletteOf(idx) else curBg = paletteOf(idx)
                        i += 2
                    } else if (kind == 2 && i + 4 < paramCount) {
                        val r = if (params[i + 2] < 0) 0 else params[i + 2]
                        val g = if (params[i + 3] < 0) 0 else params[i + 3]
                        val b = if (params[i + 4] < 0) 0 else params[i + 4]
                        val c = TerminalColor.rgb(r, g, b)
                        if (isFg) curFg = c else curBg = c
                        i += 4
                    }
                }
            }
            i++
        }
    }

    /* ================= OSC ================= */

    private fun handleOsc(s: String) {
        // 0/1/2 是设标题，先不管；4/11/12 问颜色也没实现
    }

    /* ================= 尺寸 ================= */

    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val old = screen
        val oldRows = rows
        val oldCols = cols

        rows = newRows.coerceAtLeast(1)
        cols = newCols.coerceAtLeast(1)
        val next = Array(rows) { TerminalLine(cols) }
        for (r in 0 until minOf(rows, oldRows)) {
            val src = old[r]
            val dst = next[r]
            System.arraycopy(src.text, 0, dst.text, 0, minOf(oldCols, cols))
            System.arraycopy(src.fg, 0, dst.fg, 0, minOf(oldCols, cols))
            System.arraycopy(src.bg, 0, dst.bg, 0, minOf(oldCols, cols))
            System.arraycopy(src.flags, 0, dst.flags, 0, minOf(oldCols, cols))
        }

        // 变窄的时候把溢出的行挤回历史里
        if (rows < oldRows) {
            val lost = oldRows - rows
            for (r in 0 until lost) {
                val src = old[r]
                src.resize(cols)
                if (!usingAltScreen) {
                    scrollback.addLast(src)
                    while (scrollback.size > maxScrollback) scrollback.removeFirst()
                }
            }
        }

        screen = next
        if (usingAltScreen) altScreen = null
        scrollTop = 0
        scrollBottom = rows - 1
        clampCursor()
        onChange?.invoke()
    }

    fun reset() {
        for (r in 0 until rows) screen[r].reset(curFg, curBg)
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        curFg = defaultFg
        curBg = defaultBg
        curFlags = 0
        autoWrap = true
        originMode = false
    }

    /* ================= 给渲染用 ================= */

    /** 历史 + 当前屏，一共多少行 */
    fun totalLines(): Int = scrollback.size + rows

    /**
     * 把一行里的 URL 都找出来（连同它占的列范围）。
     * 渲染时给它们画下划线，点的时候拿它判断点没点中。
     */
    fun urlsIn(line: TerminalLine): List<Pair<IntRange, String>> {
        val sb = StringBuilder(line.cols)
        for (i in 0 until line.cols) {
            val ch = line.text[i]
            sb.append(if (ch == WIDE_TAIL || ch == '\u0000') ' ' else ch)
        }
        val text = sb.toString()
        if (!text.contains("://")) return emptyList()
        return URL_REGEX.findAll(text).map { it.range to it.value }.toList()
    }

    /** 按"整条时间线"取一行：0 是最早的历史，最后是当前屏最后一行 */
    fun lineAt(index: Int): TerminalLine? {
        val sb = scrollback.size
        if (index < 0 || index >= sb + rows) return null
        return if (index < sb) scrollback.elementAt(index) else screen.getOrNull(index - sb)
    }
}

/**
 * 字符在终端里占几格。
 *
 * 只判断常见的那几块（CJK / 全角 / Emoji 区域），够用。
 */
fun charWidth(code: Int): Int = when {
    code < 0x20 -> 0
    code < 0x7F -> 1
    code in 0x1100..0x115F -> 2                       // 韩文字母
    code in 0x2E80..0x303E -> 2                       // 部首
    code in 0x3041..0x33FF -> 2                       // 假名 / 注音
    code in 0x3400..0x4DBF -> 2                       // 扩展 A
    code in 0x4E00..0x9FFF -> 2                       // 汉字
    code in 0xA000..0xA4CF -> 2                       // 彝文
    code in 0xAC00..0xD7A3 -> 2                       // 韩文音节
    code in 0xF900..0xFAFF -> 2                       // 兼容汉字
    code in 0xFE30..0xFE6F -> 2                       // 兼容形式
    code in 0xFF00..0xFF60 -> 2                       // 全角
    code in 0xFFE0..0xFFE6 -> 2
    code in 0x1F300..0x1F64F -> 2                     // Emoji
    code in 0x1F900..0x1F9FF -> 2
    code in 0x20000..0x3FFFD -> 2                     // 扩展 B 以上
    else -> 1
}
