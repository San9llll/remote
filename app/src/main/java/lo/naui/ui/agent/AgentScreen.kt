package lo.naui.ui.agent

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lo.naui.agent.AgentApi
import lo.naui.agent.AgentStore
import lo.naui.agent.ChatAttachment
import lo.naui.agent.ChatMessage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent 页。
 *
 * 走 OpenAI 兼容的 `/chat/completions`，所以 DeepSeek / OpenAI / 各种中转站
 * 只要填对 Base URL 和模型名就能用。Key、模型那些在「配置」里。
 */
@Composable
fun AgentScreen(onOpenConfig: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AgentStore.init(ctx)

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var pending by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val a = readImage(ctx, uri)
                if (a == null) error = "这张图读不了（太大或者没权限）" else pending = pending + a
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val a = readTextFile(ctx, uri)
                if (a == null) error = "这个文件读不了（只收 256KB 以内的文本）" else pending = pending + a
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        if (sending) return
        val textFiles = pending.filter { !it.isImage }
        val images = pending.filter { it.isImage }.map { it.dataUrl }
        val composed = buildString {
            textFiles.forEach { a ->
                append("【附件 ").append(a.name).append("】\n").append(a.text).append("\n\n")
            }
            append(draft)
        }.trim()

        if (composed.isBlank() && images.isEmpty()) return
        if (!AgentStore.ready) {
            error = "还没填 API Key，点右上角「配置」"
            return
        }

        val history = messages + ChatMessage("user", composed, images)
        messages = history
        draft = ""
        pending = emptyList()
        error = null
        sending = true
        scope.launch {
            runCatching { AgentApi.complete(history) }
                .onSuccess { messages = messages + ChatMessage("assistant", it) }
                .onFailure { error = it.message ?: "调用失败" }
            sending = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Agent", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (AgentStore.ready) AgentStore.model else "还没配 API Key",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onOpenConfig() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("配置", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
            }
        }

        // 消息
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("什么都没问过", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可以带图片和文本文件；配置里换个 Base URL 就是另一家的模型",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages) { m -> Bubble(m) }
                    if (sending) {
                        item(key = "__pending__") {
                            Text(
                                "对方正在输入…",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // 附件
        if (pending.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pending.forEach { a ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable { pending = pending - a }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            (if (a.isImage) "🖼 " else "📄 ") + a.name + "  ✕",
                            fontSize = 11.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        error?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 输入行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundBtn("📎") { filePicker.launch(arrayOf("*/*")) }
            RoundBtn("🖼") { imagePicker.launch("image/*") }

            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )

            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (sending || !AgentStore.ready) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)
                        } else {
                            MiuixTheme.colorScheme.primary
                        }
                    )
                    .clickable(enabled = !sending) { send() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    if (sending) "…" else "发送",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (sending || !AgentStore.ready) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.onPrimary
                    },
                )
            }
        }
    }
}

@Composable
private fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (mine) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MiuixTheme.colorScheme.surfaceContainerHigh
                )
                .padding(12.dp),
        ) {
            Column {
                if (m.images.isNotEmpty()) {
                    Text(
                        "［" + m.images.size + " 张图片］",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(m.text.ifBlank { "（空）" }, fontSize = 14.sp)
            }
        }
    }
}

/* ---------------- 附件读取 ---------------- */

private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
private const val MAX_TEXT_BYTES = 256 * 1024

private fun fileName(uri: Uri, fallback: String): String =
    uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { fallback } ?: fallback

private suspend fun readImage(ctx: Context, uri: Uri): ChatAttachment? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            if (bytes.size > MAX_IMAGE_BYTES) return@runCatching null
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ChatAttachment(
                name = fileName(uri, "image"),
                isImage = true,
                dataUrl = "data:image/jpeg;base64," + b64,
                bytes = bytes.size.toLong(),
            )
        }.getOrNull()
    }

private suspend fun readTextFile(ctx: Context, uri: Uri): ChatAttachment? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            if (bytes.size > MAX_TEXT_BYTES) return@runCatching null
            val text = String(bytes, Charsets.UTF_8)
            if (text.contains('\u0000')) return@runCatching null
            ChatAttachment(
                name = fileName(uri, "file.txt"),
                isImage = false,
                text = text,
                bytes = bytes.size.toLong(),
            )
        }.getOrNull()
    }
