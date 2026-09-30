package lo.naui.ui.terminal

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.term.TerminalCanvas
import lo.naui.term.TerminalKeys
import lo.naui.term.TerminalSession
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端。
 *
 * 底下是**真 PTY**（native 里 forkpty 出来的），上面是自己写的 xterm 模拟器。
 * 所以 vim / top / htop 这类全屏程序能正常跑 —— 它们看到的是个真终端，
 * 不是一根被重定向的管子。
 *
 * 光标键会看程序有没有开 DECCKM；Ctrl 会转成控制码；Ctrl+C 发给整个前台进程组。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }

    var tick by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(PrivLevel.Normal) }
    var scrollOffset by remember { mutableStateOf(0) }
    var cellW by remember { mutableStateOf(0f) }
    var cellH by remember { mutableStateOf(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var fontSize by remember { mutableStateOf(12.5f) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var inputBuf by remember { mutableStateOf("") }
    var sessionKey by remember { mutableStateOf(0) }

    val home = remember { File(ctx.filesDir, "home").apply { mkdirs() }.absolutePath }
    val env = remember { TerminalSession.defaultEnv(ctx.filesDir) }

    val session = remember(sessionKey) {
        TerminalSession(
            shellPath = "/system/bin/sh",
            cwd = home,
            env = env,
            rows = 24,
            cols = 80,
        )
    }

    DisposableEffect(session) {
        session.onOutput = { tick++ }
        session.onExit = { tick++ }
        session.start()
        onDispose { session.close() }
    }

    LaunchedEffect(session) { level = Privilege.level(ctx) }

    // 尺寸变了就告诉内核（vim 靠这个排版）
    LaunchedEffect(cellW, cellH, box, sessionKey) {
        if (cellW > 0f && cellH > 0f && box.width > 0 && box.height > 0) {
            val cols = (box.width / cellW).toInt().coerceAtLeast(20)
            val rows = (box.height / cellH).toInt().coerceAtLeast(4)
            session.cellWidthPx = cellW.toInt()
            session.cellHeightPx = cellH.toInt()
            session.resize(rows, cols)
            tick++
        }
    }

    val emu = session.emulator

    fun send(text: String) {
        scrollOffset = 0
        session.write(text)
    }

    fun sendKey(text: String) {
        scrollOffset = 0
        session.write(text)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "终端",
            subtitle = level.label + " · " + emu.cols + "x" + emu.rows +
                (if (session.pid > 0) " · pid " + session.pid else "") +
                (if (scrollOffset > 0) " · 回看 " + scrollOffset + " 行" else ""),
            action = "重启",
            onAction = {
                sessionKey++
                scrollOffset = 0
            },
            onBack = onBack,
        )

        // ---- 终端本体 ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101014))
                .onSizeChanged { box = it }
                .clickable { runCatching { focus.requestFocus() } },
        ) {
            TerminalCanvas(
                emulator = emu,
                fontSizeSp = fontSize,
                scrollOffset = scrollOffset,
                tick = tick,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                onCellSize = { w, h -> cellW = w; cellH = h },
                onScroll = { lines ->
                    // 往下拖（正数）是回看历史
                    val max = emu.scrollback.size
                    scrollOffset = (scrollOffset + lines).coerceIn(0, max)
                },
            )

            // 藏在后面接软键盘输入的
            BasicTextField(
                value = inputBuf,
                onValueChange = { s ->
                    when {
                        s.length > inputBuf.length -> {
                            var add = s.substring(inputBuf.length)
                            if (ctrlActive) {
                                add = TerminalKeys.ctrl(add.firstOrNull() ?: ' ') + add.drop(1)
                                ctrlActive = false
                            }
                            if (altActive) {
                                add = TerminalKeys.ESC + add
                                altActive = false
                            }
                            send(add)
                        }
                        s.length < inputBuf.length -> {
                            repeat(inputBuf.length - s.length) { sendKey(TerminalKeys.BACKSPACE) }
                        }
                    }
                    inputBuf = ""
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0f)
                    .focusRequester(focus),
                textStyle = TextStyle(fontSize = 1.sp),
                cursorBrush = SolidColor(Color.Transparent),
            )

            if (scrollOffset > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .clickable { scrollOffset = 0 }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("回到底部", fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }

        // ---- 特殊键 ----
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyChip("Ctrl", active = ctrlActive) { ctrlActive = !ctrlActive }
            KeyChip("Alt", active = altActive) { altActive = !altActive }
            KeyChip("Esc") { sendKey(TerminalKeys.ESC) }
            KeyChip("Tab") { sendKey(TerminalKeys.TAB) }
            KeyChip("↑") { sendKey(TerminalKeys.up(emu.isCursorKeysApp)) }
            KeyChip("↓") { sendKey(TerminalKeys.down(emu.isCursorKeysApp)) }
            KeyChip("←") { sendKey(TerminalKeys.left(emu.isCursorKeysApp)) }
            KeyChip("→") { sendKey(TerminalKeys.right(emu.isCursorKeysApp)) }
            KeyChip("Home") { sendKey(TerminalKeys.HOME) }
            KeyChip("End") { sendKey(TerminalKeys.END) }
            KeyChip("PgUp") { sendKey(TerminalKeys.PAGE_UP) }
            KeyChip("PgDn") { sendKey(TerminalKeys.PAGE_DOWN) }
            KeyChip("^C") { session.sendCtrlC() }
            KeyChip("^Z") { session.sendCtrlZ() }
            KeyChip("粘贴") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (!t.isNullOrEmpty()) send(t)
            }
            KeyChip("小") { if (fontSize > 8f) fontSize -= 1f }
            KeyChip("大") { if (fontSize < 24f) fontSize += 1f }
        }

        // ---- 输入框（长得像提示符）----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (ctrlActive) "^" else if (altActive) "M" else "$",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.primary,
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { runCatching { focus.requestFocus() } }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val preview = inputBuf.ifEmpty { "点这里唤起键盘 · 特殊键用上面那排" }
                Text(
                    preview,
                    fontSize = if (inputBuf.isEmpty()) 12.sp else 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (inputBuf.isEmpty()) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable { send("\r"); inputBuf = "" }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "回车",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceContainerHigh
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = if (active) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.onSurface,
        )
    }
}
