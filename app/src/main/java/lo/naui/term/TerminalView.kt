package lo.naui.term

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * 把终端缓冲画出来。
 *
 * 关键在"合并"：背景色连续相同的格子合成一个矩形、前景色 + 属性相同的连续字符
 * 合成一次 drawText —— 80x24 要是老老实实一格一格画，滚动时会掉帧。
 */
@Composable
fun TerminalCanvas(
    emulator: TerminalEmulator,
    fontSizeSp: Float,
    scrollOffset: Int,
    /** 每帧都变，用它逼着 Canvas 重画 */
    tick: Int,
    modifier: Modifier = Modifier,
    onCellSize: (Float, Float) -> Unit,
    onScroll: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val baseStyle = remember(fontSizeSp) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
    }
    val probe = remember(measurer, baseStyle) { measurer.measure("M", baseStyle) }
    val cellW = probe.size.width.toFloat()
    val cellH = probe.size.height.toFloat()

    LaunchedEffect(cellW, cellH) {
        if (cellW > 0f && cellH > 0f) onCellSize(cellW, cellH)
    }

    var acc by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier
            .pointerInput(cellH) {
                if (cellH <= 0f) return@pointerInput
                detectVerticalDragGestures { _, drag ->
                    acc += drag
                    val lines = (acc / cellH).toInt()
                    if (lines != 0) {
                        onScroll(lines)
                        acc -= lines * cellH
                    }
                }
            }
    ) {
        // 读一下 tick，保证每帧都重画
        @Suppress("UNUSED_EXPRESSION")
        tick

        val sbSize = emulator.scrollback.size
        val total = sbSize + emulator.rows
        val maxOffset = sbSize
        val offset = scrollOffset.coerceIn(0, maxOffset)
        val bottom = (total - offset).coerceIn(0, total)
        val top = (bottom - emulator.rows).coerceAtLeast(0)

        val fgDefault = TerminalColor.DEFAULT_FG
        val bgDefault = TerminalColor.DEFAULT_BG

        for (vy in 0 until emulator.rows) {
            val idx = top + vy
            val line = emulator.lineAt(idx) ?: continue
            val y = vy * cellH

            // ---- 背景：同样色连续段一笔画完 ----
            var c = 0
            while (c < emulator.cols) {
                val bg = line.bg[c]
                var end = c
                while (end + 1 < emulator.cols && line.bg[end + 1] == bg) end++
                if (bg != bgDefault) {
                    drawRect(
                        color = Color(bg),
                        topLeft = Offset(c * cellW, y),
                        size = Size((end - c + 1) * cellW, cellH),
                    )
                }
                c = end + 1
            }

            // ---- 文字：同样式连续段一次 drawText ----
            c = 0
            while (c < emulator.cols) {
                val fg = line.fg[c]
                val fl = line.flags[c]
                var end = c
                while (end + 1 < emulator.cols &&
                    line.fg[end + 1] == fg && line.flags[end + 1] == fl
                ) end++

                val cellBg = line.bg[c]
                val sb = StringBuilder(end - c + 1)
                var blank = true
                for (i in c..end) {
                    val ch = line.text[i]
                    if (ch == TerminalEmulator.WIDE_TAIL) continue   // 宽字符的第二格不画
                    if (ch != ' ') blank = false
                    sb.append(ch)
                }

                if (!blank) {
                    val flags = fl.toInt()
                    var color = if (flags and Attr.HIDDEN != 0) cellBg else fg
                    if (flags and Attr.DIM != 0) color = alphaOf(color, 0.6f)

                    if (flags and Attr.REVERSE != 0) {
                        drawRect(
                            color = Color(color),
                            topLeft = Offset(c * cellW, y),
                            size = Size((end - c + 1) * cellW, cellH),
                        )
                        color = cellBg
                    }

                    drawText(
                        textMeasurer = measurer,
                        text = sb.toString(),
                        topLeft = Offset(c * cellW, y),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSizeSp.sp,
                            color = Color(color),
                        ),
                        softWrap = false,
                        maxLines = 1,
                    )
                }
                c = end + 1
            }
        }

        // ---- 光标（回看历史时不画）----
        if (offset == 0 && emulator.cursorVisible) {
            val cy = emulator.cursorRow * cellH
            val cx = emulator.cursorCol * cellW
            drawRect(
                color = Color(TerminalColor.DEFAULT_CURSOR).copy(alpha = 0.35f),
                topLeft = Offset(cx, cy),
                size = Size(cellW, cellH),
            )
            // 空的格子给个实心块，免得看不见
            val line = emulator.lineAt(total - 1 - (emulator.rows - 1 - emulator.cursorRow))
            val ch = line?.text?.getOrNull(emulator.cursorCol)
            if (ch == null || ch == ' ') {
                drawRect(
                    color = Color(TerminalColor.DEFAULT_CURSOR).copy(alpha = 0.7f),
                    topLeft = Offset(cx + cellW * 0.2f, cy + cellH * 0.15f),
                    size = Size(cellW * 0.6f, cellH * 0.7f),
                )
            }
        }
    }
}

private fun alphaOf(color: Int, factor: Float): Int {
    val a = ((color ushr 24) and 0xFF) * factor
    return (color and 0x00FFFFFF) or ((a.toInt() and 0xFF) shl 24)
}

/** 终端默认底色，外面那层 Box 用它 */
val TerminalBackground: Color = Color(TerminalColor.DEFAULT_BG)
