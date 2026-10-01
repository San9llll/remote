@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package lo.naui.ui.agent

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import lo.naui.agent.AgentChat
import lo.naui.agent.AgentEnv
import lo.naui.agent.AgentStore
import lo.naui.agent.AgentTaskService
import lo.naui.agent.AgentTaskStore
import lo.naui.agent.ChatAttachment
import lo.naui.agent.ChatDb
import lo.naui.agent.ChatMessage
import lo.naui.agent.Persona
import lo.naui.ui.common.BottomSheet
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 一条消息在界面上的样子（比 ChatMessage 多了时间、工具记录） */
private data class UiMessage(
    val role: String,
    val text: String,
    val images: Int = 0,
    val at: Long = System.currentTimeMillis(),
    /** 工具记录（只有工具名和摘要，命令原文不在里面） */
    val toolLog: List<String> = emptyList(),
    /** 模型想了几次 */
    val thinkRounds: Int = 0,
    val usage: lo.naui.agent.AgentApi.Usage = lo.naui.agent.AgentApi.Usage(),
)

/**
 * Agent 页。
 *
 * 底下那排按用户要求改过：**文件 · 模型 · 输入栏 · 发送**。
 * 「模型」那个点开是从下面滑上来的面板，里面能改模型、人格、最大输出、执行环境。
 *
 * 气泡：用户是**白边**液态玻璃，模型是**黑边**液态玻璃。
 */
@Composable
fun AgentScreen(
    onOpenConfig: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AgentStore.init(ctx)
    ChatDb.init(ctx)

    var messages by remember { mutableStateOf<List<UiMessage>>(emptyList()) }
    var convTitle by remember { mutableStateOf("新对话") }
    var pending by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    // 这两个改成跟着后台任务走
    val task = lo.naui.agent.AgentTaskStore.state
    val sending = task.running
    var progress by remember { mutableStateOf("") }
    LaunchedEffect(task.progress) { progress = task.progress }
    var error by remember { mutableStateOf<String?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 危险动作的确认：AI 那边挂起，等用户点
    var confirmHit by remember { mutableStateOf<lo.naui.agent.DangerGuard.Hit?>(null) }
    var confirmAnswer by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    val listState = rememberLazyListState()

    // 一个文件按钮就够了（图片和文本都从这儿进，按类型自己分辨）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val mime = runCatching { ctx.contentResolver.getType(uri).orEmpty() }.getOrDefault("")
                val a = if (mime.startsWith("image/")) readImage(ctx, uri) else readTextFile(ctx, uri)
                if (a == null) error = "这个文件读不了（图片限 4MB，文本限 256KB）" else pending = pending + a
            }
        }
    }

    LaunchedEffect(Unit) {
        if (AgentStore.activeConvId == null) {
            val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ChatDb.list().firstOrNull()?.id
            }
            AgentStore.openConv(latest ?: ChatDb.newId())
        }
    }

    LaunchedEffect(AgentStore.activeConvId) {
        val id = AgentStore.activeConvId ?: return@LaunchedEffect
        val stored = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChatDb.load(id) }
        messages = stored.map { UiMessage(it.role, it.text, it.images.size, System.currentTimeMillis()) }
        convTitle = ChatDb.titleOf(stored)
    }

    fun persist(list: List<UiMessage>) {
        val id = AgentStore.activeConvId ?: return
        val plain = list.map { ChatMessage(it.role, it.text, imageCount = it.images) }
        convTitle = ChatDb.titleOf(plain)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { ChatDb.save(id, convTitle, plain) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // ---- 后台任务：切页 / 退出去再回来都能接着看 ----
    LaunchedEffect(Unit) {
        AgentTaskStore.init(ctx)
        // 回来的时候如果已经有结果在等着，取走
        AgentTaskStore.takeResult()?.let { r ->
            if (r.conversationId == AgentStore.activeConvId) {
                val text = if (r.ok) r.reply else "出错了：" + r.error
                val next = messages + UiMessage(
                    role = "assistant",
                    text = text,
                    toolLog = r.toolLog,
                    thinkRounds = r.thinkRounds,
                    usage = r.usage,
                )
                messages = next
                persist(next)
            }
        }
    }

    // 任务跑完了主动来取一次（不用退出去再进）
    val taskState = AgentTaskStore.state
    LaunchedEffect(taskState.running, taskState.finishedAt) {
        if (!taskState.running) {
            AgentTaskStore.takeResult()?.let { r ->
                if (r.conversationId == AgentStore.activeConvId) {
                    val text = if (r.ok) r.reply else "出错了：" + r.error
                    val next = messages + UiMessage(
                        role = "assistant",
                        text = text,
                        toolLog = r.toolLog,
                        thinkRounds = r.thinkRounds,
                        usage = r.usage,
                    )
                    messages = next
                    persist(next)
                }
            }
        }
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

        val user = UiMessage("user", composed, images.size)
        val history = messages + user
        messages = history
        persist(history)
        draft = ""
        pending = emptyList()
        error = null

        // 丢给前台服务去跑 —— 这样切页、退到后台都不会断
        val convId = AgentStore.activeConvId ?: ChatDb.newId()
        AgentTaskService.start(
            ctx = ctx,
            conversationId = convId,
            system = AgentStore.systemPromptNow,
            env = AgentStore.env,
            maxTokens = AgentStore.maxTokens,
            temperature = AgentStore.temperature,
            history = history.map { ChatMessage(it.role, it.text) },
        )
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ---- 顶栏 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(convTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (AgentStore.ready) {
                        AgentStore.model + " · " + AgentStore.persona.name + " · " + AgentStore.env.label
                    } else {
                        "还没配 API Key"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable { onOpenSessions() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text("会话", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable { onOpenConfig() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text("配置", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
        }

        // ---- 消息 ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没聊过", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "它能真的动手：跑命令、读写文件、看设备信息（在「模型」那个面板里选环境）",
                        fontSize = 11.5.sp,
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
                                progress.ifBlank { "对方正在输入…" },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- 附件 ----
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

        // ---- 输入栏：文件 · 模型 · 输入 · 发送 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundBtn("📎") { filePicker.launch(arrayOf("*/*")) }
            RoundBtn("⚙") { sheetOpen = true }

            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface),
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
                    color = if (sending || !AgentStore.ready) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    if (sheetOpen) {
        ModelSheet(onDismiss = { sheetOpen = false })
    }

    // ---- 危险动作确认 ----
    confirmHit?.let { hit ->
        // 底层遮罩：暗度可在主题里调（"弹窗底层背景"）
        val scrim = lo.naui.ui.theme.Prefs.current?.dialogScrim ?: 0.55f
        // 颜色得在这儿先取出来 —— onDrawSurface 是 DrawScope，里头读不了 MiuixTheme
        val sheetFill = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f)
        val sheetFillSolid = MiuixTheme.colorScheme.surface.copy(alpha = 0.94f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim)),
            contentAlignment = Alignment.Center,
        ) {
            // 磨砂玻璃：只 blur，不做折射
            val bd = lo.naui.ui.component.LocalGlassBackdrop.current
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .then(
                        if (bd != null) {
                            Modifier.drawBackdrop(
                                backdrop = bd,
                                shape = { RoundedCornerShape(20.dp) },
                                effects = { blur(24f.dp.toPx()) },
                                onDrawSurface = { drawRect(sheetFill) },
                            )
                        } else {
                            Modifier.background(sheetFillSolid)
                        }
                    )
                    .padding(20.dp),
            ) {
                Text("危险请求", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            hit.category.label,
                            fontSize = 11.5.sp,
                            color = MiuixTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        hit.category.note,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                // 中间：把具体命令摆出来
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(12.dp),
                ) {
                    Text(
                        hit.matched,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF8A80),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    hit.category.consequence,
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable { confirmAnswer?.complete(false) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("拒绝", fontSize = 13.5.sp) }

                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.error)
                            .clickable { confirmAnswer?.complete(true) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("允许执行", fontSize = 13.5.sp, color = Color.White) }
                }
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

/**
 * 气泡。
 *
 * 用户的用**白边**液态玻璃，模型的用**黑边**液态玻璃 —— 用户点名要的。
 * 时间挂在消息下面（用户的消息也带时间）。
 */
@Composable
private fun Bubble(m: UiMessage) {
    val ctx = LocalContext.current
    val mine = m.role == "user"
    val shape = RoundedCornerShape(16.dp)
    var toolsOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                // 上限，但按内容收缩
                .widthIn(max = 300.dp)
                .clip(shape),
        ) {
            GlassCard(
                backdrop = null,
                shape = shape,
                contentPadding = 13.dp,
            ) {
                Column {
                    if (m.images > 0) {
                        Text(
                            "［" + m.images + " 张图片］",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    // 正文：网址淡蓝 70% 可点开浏览器；文件路径点了进内置文件管理
                    RichText(
                        text = m.text.ifBlank { "（空）" },
                        // 自己发的不染蓝，链接高亮只给模型那边
                        highlight = !mine,
                        onOpenUrl = { url ->
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        onOpenPath = { path -> openWithBuiltinFiles(ctx, path) },
                    )

                    // ---- 想了几次 / 用了几次工具（点了才展开）----
                    if (!mine && (m.thinkRounds > 0 || m.toolLog.isNotEmpty())) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .clickable { toolsOpen = !toolsOpen }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "思考了 " + m.thinkRounds + " 次 · 用了 " + m.toolLog.size + " 次工具",
                                fontSize = 10.5.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (toolsOpen) "▾" else "▸",
                                fontSize = 10.5.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }

                        if (toolsOpen) {
                            Spacer(Modifier.height(6.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                    .padding(8.dp),
                            ) {
                                m.toolLog.forEach { line ->
                                    Text(
                                        line,
                                        fontSize = 10.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 下面那行：时间 + （模型的）一个「！」----
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            clock.format(Date(m.at)),
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (!mine && m.usage.outputTokens > 0) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (usageOpen) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { usageOpen = !usageOpen },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "!",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (usageOpen) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    // ---- 用量小窗 ----
    if (usageOpen) {
        val u = m.usage
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                UsageRow("输入 Token（缓存）", u.cachedTokens)
                UsageRow("输入 Token（其他）", u.inputTokens)
                UsageRow("输出 Token", u.outputTokens)
                UsageRow("总输入", u.totalIn)
                Spacer(Modifier.height(4.dp))
                Text(
                    "耗时 " + fmtDuration(u.millis),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.weight(1f))
        Text(value.toString(), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val m = ms / 60_000
    val s = (ms % 60_000) / 1000.0
    return if (m > 0) {
        String.format("%dm%.1fs", m, s)
    } else {
        String.format("%.1fs", s)
    }
}

/** 文件路径点了走内置文件管理，定位到它所在的目录 */
private fun openWithBuiltinFiles(ctx: Context, path: String) {
    runCatching {
        val f = java.io.File(path)
        val dir = if (f.isDirectory) f.absolutePath else (f.parent ?: f.absolutePath)
        lo.naui.sys.UiState.init(ctx)
        lo.naui.sys.UiState.saveDir(dir)
        // 让外壳切到功能页的文件管理去
        lo.naui.sys.UiState.requestOpenFiles()
    }
}

/**
 * 富文本。
 *
 * 扫描正文里的网址和文件路径：
 * - 网址 → 淡蓝色 70% 透明度，点了开浏览器
 * - 文件路径 → 同样的淡蓝，点了进内置文件管理
 */
@Composable
private fun RichText(
    text: String,
    highlight: Boolean = true,
    onOpenUrl: (String) -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val linkColor = Color(0xFF64B5F6).copy(alpha = 0.70f)

    // 不高亮就整段当普通文本画，省得白忙
    if (!highlight) {
        BasicText(
            text = text,
            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface),
        )
        return
    }

    val annotated = remember(text) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val urlStart = text.indexOf("http://", i).takeIf { it >= 0 }
                    ?: text.indexOf("https://", i).takeIf { it >= 0 }
                val pathStart = findPathStart(text, i)

                val next = listOfNotNull(urlStart, pathStart).minOrNull()
                if (next == null) {
                    append(text.substring(i))
                    break
                }
                if (next > i) append(text.substring(i, next))

                if (urlStart != null && next == urlStart) {
                    val end = scanUrlEnd(text, next)
                    val url = text.substring(next, end)
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                        )
                    ) { append(url) }
                    i = end
                } else {
                    val end = scanPathEnd(text, next)
                    val path = text.substring(next, end)
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = path,
                            styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                        ) { onOpenPath(path) }
                    ) { append(path) }
                    i = end
                }
            }
        }
    }

    BasicText(
        text = annotated,
        style = TextStyle(
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
        ),
    )
}

/** 找下一个像路径的开头：/ 开头，后面跟字母 */
private fun findPathStart(text: String, from: Int): Int? {
    var i = from
    while (i < text.length) {
        if (text[i] == '/' && i + 1 < text.length && text[i + 1].isLetter()) {
            // 前面不能是字母数字（否则是网址里的一段）
            val prev = text.getOrNull(i - 1)
            if (prev == null || (!prev.isLetterOrDigit() && prev != '/' && prev != ':')) return i
        }
        i++
    }
    return null
}

/** 碰到这些就当"这个词说完了" */
private fun isStopChar(c: Char): Boolean =
    c.isWhitespace() ||
        c in "，。、；：（）【】《》" ||
        c == '"' || c == '\'' || c == '“' || c == '”'

private fun scanUrlEnd(text: String, start: Int): Int {
    var i = start
    while (i < text.length && !isStopChar(text[i])) i++
    return i
}

private fun scanPathEnd(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (isStopChar(c)) break
        i++
    }
    return i
}

/* ---------------- 模型 / 人格 / 环境 面板 ---------------- */

@Composable
private fun ModelSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }

    BottomSheet(title = "模型 · 人格 · 环境", onDismiss = onDismiss) {
        // 模型
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(enabled = !busy) {
                        busy = true
                        msg = "正在问服务商有哪些模型…"
                        scope.launch {
                            lo.naui.agent.AgentApi.listModels()
                                .onSuccess { models = it; msg = "拿到 " + it.size + " 个" }
                                .onFailure { msg = "拿不到：" + (it.message ?: "") }
                            busy = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Text("拉取模型", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(AgentStore.model, fontSize = 12.5.sp)
        }
        if (models.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column {
                models.take(40).forEach { m ->
                    val on = m == AgentStore.model
                    Text(
                        (if (on) "● " else "○ ") + m,
                        fontSize = 12.sp,
                        color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { AgentStore.updateModel(m) }
                            .padding(vertical = 7.dp, horizontal = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // 人格
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("人格", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable {
                        editingPersona = Persona(Persona.newId(), "新人格", "")
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Text("新建", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentStore.personas.forEach { p ->
                val on = p.id == AgentStore.personaId
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (on) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { AgentStore.selectPersona(p.id); editingPersona = p }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        p.name,
                        fontSize = 12.sp,
                        color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // 最大输出
        Text("最大输出 token", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(4_096, 32_768, 128_000, 1_000_000).forEach { v ->
                val on = AgentStore.maxTokens == v
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (on) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { AgentStore.updateMaxTokens(v) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (v >= 1_000_000) "不限" else (v / 1024).toString() + "k",
                        fontSize = 11.5.sp,
                        color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // 环境
        Text("模型能用的环境", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        AgentEnv.entries.forEach { e ->
            val on = AgentStore.env == e
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { AgentStore.updateEnv(e) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (on) "● " else "○ ") + e.label,
                    fontSize = 12.5.sp,
                    color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    e.summary.take(14),
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(16.dp))
        // 危险操作怎么办（su / rm -rf 这些）
        Text("危险操作", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        lo.naui.agent.DangerGuard.Policy.entries.forEach { pol ->
            val on = AgentStore.dangerPolicy == pol
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { AgentStore.updateDangerPolicy(pol) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (on) "● " else "○ ") + pol.label,
                        fontSize = 12.5.sp,
                        color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        pol.summary,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        msg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }

    editingPersona?.let { p ->
        PersonaEditor(
            initial = p,
            onDismiss = { editingPersona = null },
            onSave = { AgentStore.savePersona(it); editingPersona = null },
            onDelete = { AgentStore.deletePersona(p.id); editingPersona = null },
            canDelete = AgentStore.personas.size > 1,
        )
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
            ChatAttachment(
                name = fileName(uri, "image"),
                isImage = true,
                dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
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
