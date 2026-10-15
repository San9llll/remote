package lo.naui.ui.book

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lo.naui.book.Book
import lo.naui.book.BookStore
import lo.naui.book.TTS_VENDORS
import lo.naui.book.TtsConfig
import lo.naui.book.TtsEngine
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一本书的配置（也是 TTS 配置）。
 *
 * 「有哪些模型」是先问官方要回来再让你挑的 ——
 * 各家模型名不一样，写死在代码里迟早过期。
 */
@Composable
fun BookConfigScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BookStore.init(ctx)
    TtsConfig.init(ctx)

    var book by remember { mutableStateOf<Book?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        book = BookStore.load(bookId)
        book?.let { TtsConfig.ensureVoices(it.characters) }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "书的配置", subtitle = book?.title ?: "", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            /* ---------------- TTS 厂商 ---------------- */
            Label("TTS 厂商")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TTS_VENDORS.forEach { v ->
                    val on = TtsConfig.vendorId == v.id
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (on) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                TtsConfig.updateVendor(v.id)
                                models = emptyList()
                                msg = null
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            v.label.substringBefore("（"),
                            fontSize = 11.5.sp,
                            color = if (on) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                TtsConfig.vendor.voiceHint.ifBlank { "按这家的文档填" },
                fontSize = 10.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(12.dp))
            Label("API Key")
            Input(TtsConfig.apiKey, "sk-…") { TtsConfig.updateApiKey(it) }

            Spacer(Modifier.height(10.dp))
            Label("Base URL（留空用这家的默认：" + TtsConfig.vendor.baseUrl + "）")
            Input(TtsConfig.baseUrlOverride, "https://…") { TtsConfig.updateBaseUrl(it) }

            /* ---------------- 模型 ---------------- */
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("模型")
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable(enabled = !busy) {
                            busy = true
                            msg = "正在问官方有哪些模型…"
                            scope.launch {
                                TtsEngine.listModels()
                                    .onSuccess {
                                        models = it
                                        msg = "拿到 " + it.size + " 个模型"
                                    }
                                    .onFailure { msg = "拿不到模型列表：" + (it.message ?: "") }
                                busy = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text("拉取模型列表", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Input(TtsConfig.useModel, TtsConfig.vendor.defaultModel) { TtsConfig.updateModel(it) }

            if (models.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    models.take(30).forEach { m ->
                        val on = m == TtsConfig.useModel
                        Text(
                            (if (on) "● " else "○ ") + m,
                            fontSize = 11.5.sp,
                            color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { TtsConfig.updateModel(m) }
                                .padding(vertical = 5.dp, horizontal = 6.dp),
                        )
                    }
                }
            }

            /* ---------------- 格式 / 语速 ---------------- */
            Spacer(Modifier.height(12.dp))
            Label("输出格式（wav 最好拼接）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("wav", "mp3", "pcm").forEach { f ->
                    val on = TtsConfig.format == f
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (on) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { TtsConfig.updateFormat(f) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            f,
                            fontSize = 11.5.sp,
                            color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            /* ---------------- 每个角色的音色 ---------------- */
            val b = book
            if (b != null) {
                Spacer(Modifier.height(16.dp))
                Label("音色分配（旁白 / 每个角色分开合成，再拼成一整段）")
                VoiceRow(TtsConfig.NARRATOR)
                b.characters.forEach { c ->
                    VoiceRow(c.name)
                    Text(
                        "　角色：" + c.role,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            msg?.let {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp),
                ) {
                    Text(it, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun VoiceRow(character: String) {
    val vendor = TtsConfig.vendor
    val current = TtsConfig.voiceMap[character].orEmpty()
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(
            character,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            vendor.voices.forEach { v ->
                val on = current == v
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (on) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { TtsConfig.setVoice(character, v) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        v,
                        fontSize = 10.5.sp,
                        color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Input(current, "或者自己填音色名 / 音色 ID") { TtsConfig.setVoice(character, it) }
    }
}

@Composable
private fun Label(t: String) {
    Text(
        t,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Input(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        )
        if (value.isEmpty()) {
            Text(hint, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
