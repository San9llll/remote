package lo.naui.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.launch
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.term.Backup
import lo.naui.term.Bootstrap
import lo.naui.term.ColorSchemes
import lo.naui.term.ExtraKey
import lo.naui.term.ExtraKeys
import lo.naui.term.ModifierKey
import lo.naui.term.TerminalCanvas
import lo.naui.term.TerminalKeys
import lo.naui.term.TerminalSession
import lo.naui.term.TerminalSettings
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端。
 *
 * 底层是真 PTY（native forkpty），上面是自己写的 xterm 模拟器。
 * 交互照 termux 那套来：**左右滑切会话**、长按选区复制、功能键两排、
 * 配色/字号/开关都有面板可调。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    TerminalSettings.init(ctx)
    val scheme = TerminalSettings.scheme

    var tick by remember { mutableIntStateOf(0) }
    var level by remember { mutableStateOf(PrivLevel.Normal) }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    val home = remember { File(ctx.filesDir, "home").apply { mkdirs() }.absolutePath }
    val env = remember { TerminalSession.defaultEnv(ctx.filesDir) }

    // 终端开着的时候别锁屏
    val view = LocalView.current
    DisposableEffect(TerminalSettings.keepScreenOn) {
        view.keepScreenOn = TerminalSettings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val installed = Bootstrap.isInstalled(ctx)

    fun makeSession(): TerminalSession = TerminalSession(
        shellPath = Bootstrap.shellPath(ctx),
        cwd = if (installed) Bootstrap.home(ctx).absolutePath else home,
        env = if (installed) Bootstrap.environ(ctx) else env,
        rows = 24,
        cols = 80,
        palette = scheme.palette,
        defaultFg = scheme.fg,
        defaultBg = scheme.bg,
        maxScrollback = TerminalSettings.scrollbackLines,
    )

    var sessions by remember { mutableStateOf<List<TerminalSession>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) {
            val s = makeSession()
            s.onOutput = { tick++ }
            s.onExit = { tick++ }
            s.start()
            sessions = listOf(s)
        }
        level = Privilege.level(ctx)
    }

    // 换配色时，把已经开着的会话也刷一遍
    LaunchedEffect(scheme.id) {
        sessions.forEach { s -> s.emulator.applyPalette(scheme.palette, scheme.fg, scheme.bg) }
        tick++
    }

    DisposableEffect(Unit) {
        onDispose { sessions.forEach { runCatching { it.close() } } }
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { maxOf(sessions.size, 1) })

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "终端",
            subtitle = level.label +
                " · " + sessions.size + " 个会话" +
                (if (sessions.size > 1) " · 左右滑切换" else ""),
            action = "新建",
            onAction = {
                val s = makeSession()
                s.onOutput = { tick++ }
                s.onExit = { tick++ }
                s.start()
                sessions = sessions + s
            },
            onBack = onBack,
        )

        // 没装 Termux 环境就先引导装
        if (!installed) {
            BootstrapBar(onDone = { tick++ })
            Spacer(Modifier.height(2.dp))
        }

        // 会话条：点一下切，长按关掉
        if (sessions.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sessions.forEachIndexed { i, s ->
                    val active = pagerState.currentPage == i
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { showSessions = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "#" + (i + 1) + if (s.running) "" else " ✗",
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (active) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (sessions.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1,
            ) { page ->
                val session = sessions.getOrNull(page)
                if (session != null) {
                    TerminalPage(
                        session = session,
                        tick = tick,
                        onTick = { tick++ },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }

    if (showSettings) {
        TerminalSettingsPanel(onDismiss = { showSettings = false })
    }

    if (showSessions) {
        SessionPanel(
            sessions = sessions,
            currentPage = pagerState.currentPage,
            onSelect = { i -> scope.launch { pagerState.scrollToPage(i) } },
            onClose = { i ->
                val s = sessions.getOrNull(i)
                runCatching { s?.close() }
                sessions = sessions.filterIndexed { idx, _ -> idx != i }
                showSessions = false
            },
            onDismiss = { showSessions = false },
        )
    }
}

/* ---------------- 一页终端 ---------------- */

@Composable
private fun TerminalPage(
    session: TerminalSession,
    tick: Int,
    onTick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    val emu = session.emulator
    val scheme = TerminalSettings.scheme

    var scrollOffset by remember { mutableStateOf(0) }
    var cellW by remember { mutableStateOf(0f) }
    var cellH by remember { mutableStateOf(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var inputBuf by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selStart by remember { mutableStateOf(-1) }
    var selText by remember { mutableStateOf("") }

    LaunchedEffect(cellW, cellH, box) {
        if (cellW > 0f && cellH > 0f && box.width > 0 && box.height > 0) {
            val cols = (box.width / cellW).toInt().coerceAtLeast(20)
            val rows = (box.height / cellH).toInt().coerceAtLeast(4)
            session.cellWidthPx = cellW.toInt()
            session.cellHeightPx = cellH.toInt()
            session.resize(rows, cols)
            onTick()
        }
    }

    // ---- 进程响铃就震一下 ----
    val vibrator = remember {
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    DisposableEffect(session) {
        session.onBell = {
            if (TerminalSettings.bellVibrate && vibrator?.hasVibrator() == true) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(35L, 90))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(35L)
                    }
                }
            }
        }
        onDispose { session.onBell = null }
    }

    fun cellAt(offset: Offset): Int {
        if (cellW <= 0f || cellH <= 0f) return -1
        val col = (offset.x / cellW).toInt().coerceIn(0, emu.cols - 1)
        val row = (offset.y / cellH).toInt().coerceIn(0, emu.rows - 1)
        return row * emu.cols + col
    }

    fun extractSelection(sel: Set<Int>): String {
        if (sel.isEmpty()) return ""
        val min = sel.min()
        val max = sel.max()
        val sb = StringBuilder()
        for (i in min..max) {
            val r = i / emu.cols
            val c = i % emu.cols
            if (c == 0 && sb.isNotEmpty()) sb.append('\n')
            val line = emu.lineAt(emu.totalLines() - emu.rows + r) ?: continue
            val ch = line.text.getOrNull(c) ?: ' '
            if (ch != '\u0000') sb.append(ch)
        }
        return sb.toString().trimEnd()
    }

    fun send(text: String) {
        scrollOffset = 0
        session.write(text)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(scheme.bg))
                .onSizeChanged { box = it }
                // 双指缩放字号 —— 只在两指时吃事件，单指的不碰，
                // 所以下面的长按选区和上下拖滚动都还照常
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var lastSpan = 0f
                        var zooming = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val span = (pressed[0].position - pressed[1].position).getDistance()
                                if (zooming && lastSpan > 1f && span > 1f) {
                                    val factor = span / lastSpan
                                    if (factor != 1f) {
                                        TerminalSettings.updateFontSize(TerminalSettings.fontSize * factor)
                                        onTick()
                                    }
                                }
                                lastSpan = span
                                zooming = true
                                pressed.forEach { it.consume() }
                            }
                        }
                    }
                }
                .pointerInput(emu.cols, emu.rows, cellW, cellH) {
                    detectTapGestures { pos ->
                        if (selection.isNotEmpty()) {
                            selection = emptySet()
                            return@detectTapGestures
                        }
                        val col = if (cellW > 0f) (pos.x / cellW).toInt() else -1
                        val row = if (cellH > 0f) (pos.y / cellH).toInt() else -1
                        if (col < 0 || row < 0 || row >= emu.rows) {
                            runCatching { focus.requestFocus() }
                            return@detectTapGestures
                        }
                        val line = emu.lineAt(emu.totalLines() - emu.rows + row)
                        val hit = if (line != null && TerminalSettings.openUrls) {
                            emu.urlsIn(line).firstOrNull { col in it.first }
                        } else null
                        if (hit != null) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(hit.second))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        } else {
                            runCatching { focus.requestFocus() }
                        }
                    }
                }
                .pointerInput(emu.cols, emu.rows, cellW, cellH) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            val idx = cellAt(pos)
                            if (idx >= 0) {
                                selStart = idx
                                selection = setOf(idx)
                                selText = ""
                            }
                        },
                        onDrag = { change, _ ->
                            val idx = cellAt(change.position)
                            if (idx >= 0 && selStart >= 0) {
                                val a = minOf(selStart, idx)
                                val b = maxOf(selStart, idx)
                                selection = (a..b).toSet()
                                selText = extractSelection(selection)
                            }
                        },
                        onDragEnd = {
                            if (selection.isEmpty()) selText = ""
                        },
                    )
                },
        ) {
            TerminalCanvas(
                emulator = emu,
                fontSizeSp = TerminalSettings.fontSize,
                scrollOffset = scrollOffset,
                tick = tick,
                fgDefault = scheme.fg,
                bgDefault = scheme.bg,
                cursorColor = scheme.cursor,
                showUrls = TerminalSettings.openUrls,
                selection = selection,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                onCellSize = { w, h -> cellW = w; cellH = h },
                onScroll = { lines ->
                    val max = emu.scrollback.size
                    scrollOffset = (scrollOffset + lines).coerceIn(0, max)
                },
            )

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
                            repeat(inputBuf.length - s.length) { send(TerminalKeys.BACKSPACE) }
                        }
                    }
                    inputBuf = ""
                },
                modifier = Modifier.fillMaxSize().alpha(0f).focusRequester(focus),
                textStyle = TextStyle(fontSize = 1.sp),
                cursorBrush = SolidColor(Color.Transparent),
            )

            if (selection.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 " + selection.size + " 格",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    SmallAction("复制") {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("terminal", selText))
                        selection = emptySet()
                        onTick()
                    }
                    SmallAction("全选") {
                        selection = (0 until emu.rows * emu.cols).toSet()
                        selText = extractSelection(selection)
                    }
                    if (selText.startsWith("http://") || selText.startsWith("https://")) {
                        SmallAction("打开") {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(selText))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            selection = emptySet()
                        }
                    }
                    SmallAction("取消") { selection = emptySet() }
                }
            }

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

        // ---- 功能键 ----
        val rows = TerminalSettings.extraKeysRows
        if (rows >= 1) {
            KeyRow(ExtraKeys.ROW1, emu.isCursorKeysApp, ctrlActive, altActive,
                onCtrl = { ctrlActive = !ctrlActive },
                onAlt = { altActive = !altActive },
                onKey = { k -> ExtraKeys.resolve(k, emu.isCursorKeysApp)?.let { send(it) } },
                onSettings = onOpenSettings,
                extra = { SmallAction("^C") { session.sendCtrlC() } })
        }
        if (rows >= 2) {
            KeyRow(ExtraKeys.ROW2, emu.isCursorKeysApp, ctrlActive, altActive,
                onCtrl = { ctrlActive = !ctrlActive },
                onAlt = { altActive = !altActive },
                onKey = { k -> ExtraKeys.resolve(k, emu.isCursorKeysApp)?.let { send(it) } },
                onSettings = null,
                extra = {
                    SmallAction("粘贴") {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.text?.toString()
                        if (!t.isNullOrEmpty()) send(t)
                    }
                    SmallAction("⚙") { onOpenSettings() }
                })
        } else if (rows == 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallAction("粘贴") {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString()
                    if (!t.isNullOrEmpty()) send(t)
                }
                SmallAction("⚙") { onOpenSettings() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallAction("⚙ 设置") { onOpenSettings() }
            }
        }

        // ---- 输入行 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
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
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    inputBuf.ifEmpty { "点这里唤起键盘" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (inputBuf.isEmpty()) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onSurface,
                )
            }
            SmallAction("回车") { send("\r") }
        }
    }
}

@Composable
private fun KeyRow(
    keys: List<ExtraKey>,
    app: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (ExtraKey) -> Unit,
    onSettings: (() -> Unit)?,
    extra: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        keys.forEach { k ->
            val active = (k.modifier == ModifierKey.CTRL && ctrl) ||
                (k.modifier == ModifierKey.ALT && alt)
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable {
                        when (k.modifier) {
                            ModifierKey.CTRL -> onCtrl()
                            ModifierKey.ALT -> onAlt()
                            else -> onKey(k)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    k.label,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        extra()
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
    }
}

/* ---------------- 设置面板 ---------------- */

@Composable
private fun TerminalSettingsPanel(onDismiss: () -> Unit) {
    val scheme = TerminalSettings.scheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surface)
                .clickable { }
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("终端设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            Text("配色", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(6.dp))
            ColorSchemes.LIST.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { cs ->
                        val on = cs.id == scheme.id
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else MiuixTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable { TerminalSettings.updateScheme(cs.id) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(cs.bg)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                cs.name,
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = if (on) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("字号 " + TerminalSettings.fontSize.toInt(), fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallAction("小") { TerminalSettings.updateFontSize(TerminalSettings.fontSize - 1f) }
                SmallAction("大") { TerminalSettings.updateFontSize(TerminalSettings.fontSize + 1f) }
                SmallAction("重置") { TerminalSettings.updateFontSize(12.5f) }
            }

            Spacer(Modifier.height(14.dp))
            Text("功能键行数", fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0 to "不显示", 1 to "一排", 2 to "两排").forEach { (v, label) ->
                    val on = TerminalSettings.extraKeysRows == v
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (on) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { TerminalSettings.updateExtraKeysRows(v) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 11.5.sp,
                            color = if (on) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            TerminalSwitch("保持屏幕常亮", TerminalSettings.keepScreenOn) {
                TerminalSettings.updateKeepScreenOn(it)
            }
            TerminalSwitch("响铃震动", TerminalSettings.bellVibrate) {
                TerminalSettings.updateBellVibrate(it)
            }
            TerminalSwitch("音量键当 Ctrl", TerminalSettings.volumeKeysAsCtrl) {
                TerminalSettings.updateVolumeKeysAsCtrl(it)
            }
            TerminalSwitch("识别并打开链接", TerminalSettings.openUrls) {
                TerminalSettings.updateOpenUrls(it)
            }

            Spacer(Modifier.height(14.dp))
            Text("回看行数 " + TerminalSettings.scrollbackLines, fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1000, 4000, 10000, 20000).forEach { v ->
                    SmallAction(if (v >= 1000) (v / 1000).toString() + "k" else v.toString()) {
                        TerminalSettings.updateScrollback(v)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("环境 · 备份", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(6.dp))
            BackupSection()

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable { onDismiss() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "关闭",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun TerminalSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (checked) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                )
                .clickable { onChange(!checked) }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                if (checked) "开" else "关",
                fontSize = 11.5.sp,
                color = if (checked) MiuixTheme.colorScheme.onPrimary else Color.White,
            )
        }
    }
}

/* ---------------- 会话列表 ---------------- */

@Composable
private fun SessionPanel(
    sessions: List<TerminalSession>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surface)
                .clickable { }
                .padding(18.dp),
        ) {
            Text("会话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            sessions.forEachIndexed { i, s ->
                val active = i == currentPage
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { onSelect(i); onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "#" + (i + 1),
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (active) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        (if (s.running) "运行中" else "已退出") +
                            (if (s.pid > 0) " · pid " + s.pid else ""),
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    SmallAction("关闭") { onClose(i) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onDismiss() }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", fontSize = 13.sp)
            }
        }
    }
}

/* ---------------- 环境安装条 ---------------- */

@Composable
private fun BootstrapBar(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showDiag by remember { mutableStateOf(false) }

    // 网络下不来的时候，自己下好 zip 从这儿喂进来
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            runCatching {
                val tmp = java.io.File(ctx.cacheDir, "user-bootstrap.zip")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tmp).use { out -> input.copyTo(out) }
                }
                if (tmp.length() < 512L * 1024L) {
                    throw IllegalStateException("这个文件才 " + (tmp.length() / 1024) + "KB，不像 bootstrap")
                }
                Bootstrap.installFromUserZip(ctx, tmp) { pr, st ->
                    progress = pr
                    status = st + " " + (pr * 100).toInt() + "%"
                }.getOrThrow()
            }.onSuccess {
                status = "装好了"
                onDone()
            }.onFailure {
                error = it.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("还没装 Termux 环境", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (busy) status else "装完才有 bash / pkg / apt / git / python",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (busy) MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        else MiuixTheme.colorScheme.primary
                    )
                    .clickable(enabled = !busy) {
                        busy = true
                        error = null
                        progress = 0f
                        status = "开始…"
                        scope.launch {
                            Bootstrap.install(ctx) { pr, st ->
                                progress = pr
                                status = st + " " + (pr * 100).toInt() + "%"
                            }
                                .onSuccess {
                                    status = "装好了"
                                    onDone()
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    if (busy) "安装中" else "在线装",
                    fontSize = 12.5.sp,
                    color = if (busy) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = !busy) { zipPicker.launch(arrayOf("*/*")) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("从文件装", fontSize = 12.5.sp)
            }
        }

        if (busy) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary)
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.error.copy(alpha = 0.12f))
                    .padding(10.dp),
            ) {
                Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(if (showDiag) "收起体检" else "体检") { showDiag = !showDiag }
            SmallAction("清掉半装的") {
                Bootstrap.uninstall(ctx)
                status = ""
                error = null
                onDone()
            }
        }
        if (showDiag) {
            Spacer(Modifier.height(6.dp))
            Bootstrap.diagnose(ctx).forEach { (label, ok) ->
                Text(
                    (if (ok) "✓ " else "✗ ") + label,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                )
            }
            Text(
                "缓存包：" + Bootstrap.cachedZip(ctx).absolutePath,
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/* ---------------- 备份 / 恢复 ---------------- */

@Composable
private fun BackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val installed = Bootstrap.isInstalled(ctx)

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (installed) {
                "已装 · 占 " + (Bootstrap.installedSize(ctx) / 1024.0 / 1024.0).let {
                    String.format("%.0f MB", it)
                }
            } else {
                "没装环境，备份也没东西可备"
            },
            fontSize = 11.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("备份") {
                if (!installed || busy) return@SmallAction
                busy = true
                msg = null
                scope.launch {
                    val dir = Backup.defaultDir().let { if (it.exists()) it else ctx.filesDir }
                    val out = File(dir, Backup.suggestName())
                    Backup.backup(ctx, out)
                        .onSuccess { msg = "备份好了：" + it.absolutePath }
                        .onFailure { msg = "备份失败：" + it.message }
                    busy = false
                }
            }
            SmallAction("恢复") {
                if (busy) return@SmallAction
                val dir = Backup.defaultDir().let { if (it.exists()) it else ctx.filesDir }
                val list = Backup.listBackups(dir)
                if (list.isEmpty()) {
                    msg = "没找到备份（放在 " + dir.absolutePath + "）"
                    return@SmallAction
                }
                busy = true
                msg = null
                scope.launch {
                    Backup.restore(ctx, list.first())
                        .onSuccess { msg = "恢复好了，重启会话生效" }
                        .onFailure { msg = "恢复失败：" + it.message }
                    busy = false
                }
            }
            SmallAction("卸载环境") {
                if (busy) return@SmallAction
                Bootstrap.uninstall(ctx)
                msg = "已删掉 files/usr"
            }
        }
        // ---- apt 源 ----
        Spacer(Modifier.height(12.dp))
        Text(
            "apt 源（装完环境才有用）",
            fontSize = 11.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Bootstrap.MIRRORS.forEach { m ->
                SmallAction(m.label) {
                    scope.launch {
                        Bootstrap.setMirror(ctx, m)
                            .onSuccess { msg = "源换成了：" + m.label + "（pkg update 生效）" }
                            .onFailure { msg = "换源失败：" + it.message }
                    }
                }
            }
        }

        msg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
