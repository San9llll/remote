package lo.naui.ui.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TermBg = Color(0xFF101014)
private val TermFg = Color(0xFFE6E6E6)
private val TermDim = Color(0xFF8A8A94)
private val TermPrompt = Color(0xFF7FD1FF)

private const val START_DIR = "/storage/emulated/0"

private val QUICK = listOf(
    "ls -al", "pwd", "whoami", "id", "df -h", "free -m",
    "ps -A", "getprop | head", "cat /proc/cpuinfo | head", "su -c id", "dumpsys battery",
)

/**
 * 终端。
 *
 * 是「命令执行器」那一路：一行命令一个结果，维护 cd 的状态，
 * 按当前最高权限去跑（root → su，Shizuku → 它的 shell，都没有 → 本地 sh）。
 *
 * 不是完整 Termux —— 没有 pty，所以 `vi` / `top` 这种要接管屏幕的
 * 交互式程序跑不了，它们会直接结束或者刷一屏就退出。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var cwd by remember { mutableStateOf(START_DIR) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(PrivLevel.Normal) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        level = Privilege.level(ctx)
        lines = listOf(
            "Nakour 终端 · 当前权限：" + level.label,
            "cd 会记住；环境变量不保留；要 root 就用 su -c 开头",
            "",
        )
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    fun submit(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        lines = lines + (level.label + ":" + cwd + " $ " + cmd)
        input = ""

        if (cmd == "clear" || cmd == "cls") {
            lines = emptyList()
            return
        }

        scope.launch {
            busy = true
            // cd 是内建命令：shell 里 cd 不会影响下一个进程，得自己记着
            if (cmd == "cd" || cmd.startsWith("cd ")) {
                val arg = cmd.removePrefix("cd").trim()
                val target = when {
                    arg.isEmpty() || arg == "~" -> START_DIR
                    arg.startsWith("/") -> arg
                    else -> cwd.trimEnd('/') + "/" + arg
                }
                val out = runShell(ctx, level, cwd, "cd '" + target.replace("'", "'\\''") + "' && pwd")
                val p = out.trim().lines().lastOrNull { it.startsWith("/") }
                if (p != null) cwd = p else lines = lines + (out.ifBlank { "cd: 进不去" })
            } else {
                val out = runShell(ctx, level, cwd, cmd)
                lines = lines + out.split("\n")
            }
            if (lines.size > 2000) lines = lines.takeLast(1500)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "终端",
            subtitle = level.label + " · " + cwd,
            action = "清屏",
            onAction = { lines = emptyList() },
            onBack = onBack,
        )

        // 输出
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(TermBg)
        ) {
            if (lines.isEmpty()) {
                Text(
                    "输入命令，回车执行",
                    fontSize = 12.sp,
                    color = TermDim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line.ifEmpty { " " },
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                line.contains("$ ") -> TermPrompt
                                else -> TermFg
                            },
                        )
                    }
                }
            }
        }

        // 快捷命令
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK.forEach { q ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable { submit(q) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(q, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // 输入行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "$",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.primary,
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (busy) MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)
                        else MiuixTheme.colorScheme.primary
                    )
                    .clickable(enabled = !busy) { submit(input) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    if (busy) "…" else "运行",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (busy) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** 按当前最高权限跑一条命令 */
private suspend fun runShell(ctx: Context, level: PrivLevel, cwd: String, cmd: String): String =
    withContext(Dispatchers.IO) {
        val wrapped = "cd '" + cwd.replace("'", "'\\''") + "' 2>/dev/null; " + cmd
        if (level != PrivLevel.Normal) {
            val out = Privilege.exec(ctx, wrapped)
            if (out != null) return@withContext out.ifBlank { "" }
        }
        localSh(wrapped)
    }

/** 没有 root / Shizuku 时，用 App 自己的 sh（只能碰自己有权碰的地方） */
private fun localSh(cmd: String): String = runCatching {
    val p = ProcessBuilder("sh", "-c", cmd)
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
    p.destroy()
    out.ifBlank { "" }
}.getOrDefault("")
