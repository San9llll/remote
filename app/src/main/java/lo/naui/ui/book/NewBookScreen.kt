package lo.naui.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import lo.naui.agent.AgentStore
import lo.naui.book.Book
import lo.naui.book.BookAi
import lo.naui.book.BookChapter
import lo.naui.book.BookCharacter
import lo.naui.book.BookStore
import lo.naui.book.TtsConfig
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建一本书。
 *
 * 填「初始词」和人物，点「生成开头」—— 它会拿这些 + 小说家的提示词去调 AI，
 * 出来的正文直接作为第一章。书里的人物名会顺手铺上 TTS 音色。
 */
@Composable
fun NewBookScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BookStore.init(ctx)
    TtsConfig.init(ctx)

    var title by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    var chars by remember { mutableStateOf<List<BookCharacter>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "写一本新的", subtitle = "填完点生成，AI 给写开头", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            FieldLabel("书名（留空就让 AI 起）")
            Input(title, "比如：雨夜列车") { title = it }
            Spacer(Modifier.height(14.dp))

            FieldLabel("初始词")
            Input(
                seed,
                "想写个什么样的故事？人物、背景、开局都行……",
                singleLine = false,
                minHeight = 110.dp,
            ) { seed = it }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("人物")
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { chars = chars + BookCharacter("", "") }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text("+ 加一个", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (chars.isEmpty()) {
                Text(
                    "还没加人物",
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            chars.forEachIndexed { i, c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        Input(c.name, "姓名") { v ->
                            chars = chars.toMutableList().also { it[i] = c.copy(name = v) }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        Input(c.role, "角色，比如 女主") { v ->
                            chars = chars.toMutableList().also { it[i] = c.copy(role = v) }
                        }
                    }
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable { chars = chars.filterIndexed { idx, _ -> idx != i } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (!AgentStore.ready) {
                Hint("还没配 AI 的 API Key —— 去 Agent 页右上角「配置」填一下，那边和这里是同一份配置")
            }
            error?.let { Hint(it, isError = true) }

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (busy || !AgentStore.ready) MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        else MiuixTheme.colorScheme.primary
                    )
                    .clickable(enabled = !busy && AgentStore.ready) {
                        busy = true
                        error = null
                        scope.launch {
                            // 先落一本空书，失败了也不至于全丢
                            val id = BookStore.newId()
                            var t = title.trim()
                            status = "正在起书名…"
                            if (t.isBlank()) {
                                t = BookAi.suggestTitle(seed, chars).getOrDefault("").ifBlank { "无名之书" }
                            }

                            status = "AI 正在写开头…"
                            val text = BookAi.generateOpening(
                                Book(id, t, seed, chars, emptyList(), System.currentTimeMillis(), System.currentTimeMillis())
                            ).getOrElse {
                                error = it.message
                                busy = false
                                status = ""
                                return@launch
                            }

                            val chapter = BookChapter(0, "第一章", text)
                            val book = Book(
                                id = id, title = t, seed = seed, characters = chars,
                                chapters = listOf(chapter),
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                            )
                            BookStore.save(book)
                            // 给人物铺上默认音色
                            runCatching {
                                TtsConfig.ensureVoices(chars)
                            }

                            busy = false
                            status = ""
                            onCreated(id)
                        }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        busy -> status.ifBlank { "生成中…" }
                        !AgentStore.ready -> "先去配 AI"
                        else -> "生成开头"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (busy || !AgentStore.ready) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun FieldLabel(t: String) {
    Text(
        t,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Input(
    value: String,
    hint: String,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .then(if (minHeight > 0.dp) Modifier.height(minHeight) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            textStyle = TextStyle(
                fontSize = 13.5.sp,
                color = MiuixTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        )
        if (value.isEmpty()) {
            Text(
                hint,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isError) MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                else MiuixTheme.colorScheme.surfaceContainerHigh
            )
            .padding(10.dp),
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            color = if (isError) MiuixTheme.colorScheme.error
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
