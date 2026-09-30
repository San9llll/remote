@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package lo.naui.ui.book

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lo.naui.book.AudioMerge
import lo.naui.book.Book
import lo.naui.book.BookAi
import lo.naui.book.BookChapter
import lo.naui.book.BookStore
import lo.naui.book.TtsConfig
import lo.naui.book.TtsEngine
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 阅读页。
 *
 * 和 Agent 页长得不一样：**没有下面那条消息栏**，
 * 顶上书名是大标题，右边两个不带底的按钮（插入 / 配置），
 * 底下只有窄窄一条：上一章 · 圆形的朗读键 · 下一章。
 */
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenConfig: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BookStore.init(ctx)
    TtsConfig.init(ctx)

    var book by remember { mutableStateOf<Book?>(null) }
    var chapterIndex by remember { mutableStateOf(0) }
    var insertOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(bookId, tick) {
        val b = withContext(Dispatchers.IO) { BookStore.load(bookId) }
        book = b
        if (b != null) chapterIndex = chapterIndex.coerceIn(0, (b.chapters.size - 1).coerceAtLeast(0))
    }

    // 播放器
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0f) }

    DisposableEffect(bookId) {
        onDispose {
            runCatching { player.value?.release() }
            player.value = null
        }
    }

    val current = book?.chapters?.getOrNull(chapterIndex)

    fun stop() {
        runCatching { player.value?.stop() }
        runCatching { player.value?.release() }
        player.value = null
        playing = false
    }

    fun play(path: String) {
        stop()
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
            }
            mp.setOnCompletionListener { playing = false }
            mp.start()
            player.value = mp
            playing = true
        }.onFailure {
            hint = "播放失败：" + (it.message ?: "")
        }
    }

    /** 生成整章的朗读：分角色合成 → 合并 → 落盘 */
    fun generateAudio(ch: BookChapter) {
        scope.launch {
            generating = "正在分角色…"
            val segments = BookAi.segmentsForSpeech(book!!, ch).getOrElse {
                generating = ""
                hint = "分角色失败：" + (it.message ?: "")
                return@launch
            }
            val dir = BookStore.audioDir(ctx)
            val parts = mutableListOf<ByteArray>()

            segments.forEachIndexed { i, seg ->
                generating = "正在合成 " + (i + 1) + "/" + segments.size + "（" + seg.speaker + "）"
                val voice = TtsConfig.voiceOf(seg.speaker)
                val bytes = TtsEngine.synthesize(seg.text, voice, seg.style).getOrElse {
                    generating = ""
                    hint = "合成失败（" + seg.speaker + "）：" + (it.message ?: "")
                    return@launch
                }
                parts += bytes
            }

            generating = "正在合并…"
            val merged = AudioMerge.merge(parts)
            if (merged == null) {
                generating = ""
                hint = "合并失败：没有音频"
                return@launch
            }

            val ext = if (TtsConfig.format == "wav") "wav" else TtsConfig.format
            val out = File(dir, book!!.id + "_" + ch.index + "." + ext)
            withContext(Dispatchers.IO) { out.writeBytes(merged) }

            // 记回书里
            val b = book!!
            val chapters = b.chapters.toMutableList()
            chapters[ch.index] = chapters[ch.index].copy(audioPath = out.absolutePath)
            val next = b.copy(chapters = chapters, updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { BookStore.save(next) }
            book = next
            generating = ""
            onChanged()
            play(out.absolutePath)
        }
    }

    val b = book ?: return
    val ch = current

    Column(Modifier.fillMaxSize()) {
        /* ---------------- 顶栏 ---------------- */
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    b.title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ch != null) {
                    Text(
                        ch.title + " · 第 " + (chapterIndex + 1) + "/" + b.chapters.size + " 章",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            // 两个无底按钮
            FlatButton("插入") { insertOpen = true }
            FlatButton("配置") { onOpenConfig() }
        }

        hint?.let {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.error.copy(alpha = 0.12f))
                    .clickable { hint = null }
                    .padding(10.dp),
            ) {
                Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.error)
            }
        }
        if (generating.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(10.dp),
            ) {
                Text(generating, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
            }
        }

        /* ---------------- 正文 ---------------- */
        if (ch == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("这本书还没有章节", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(ch.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    ch.content,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(28.dp))
            }
        }

        /* ---------------- 底部：窄窄一条 ---------------- */
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ChapterButton("上一章", enabled = chapterIndex > 0) {
                stop()
                chapterIndex--
            }

            // 中间那个圆：点一下开始/停止，长按调速 / 清朗读
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        if (playing) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
                    )
                    .combinedClickable(
                        onClick = {
                            val c = ch ?: return@combinedClickable
                            if (playing) {
                                stop()
                            } else {
                                val path = c.audioPath
                                if (path.isNotBlank() && File(path).exists()) {
                                    play(path)
                                } else {
                                    if (!TtsConfig.ready) {
                                        hint = "还没配 TTS —— 点右上角「配置」"
                                    } else {
                                        generateAudio(c)
                                    }
                                }
                            }
                        },
                        onLongClick = { speedOpen = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        generating.isNotBlank() -> "…"
                        playing -> "■"
                        else -> "▶"
                    },
                    fontSize = 18.sp,
                    color = if (playing) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.primary,
                )
            }

            ChapterButton("下一章", enabled = book!!.chapters.size > chapterIndex + 1) {
                stop()
                chapterIndex++
            }
        }
    }

    /* ---------------- 插入：跟 AI 聊，不写进正文 ---------------- */
    if (insertOpen && ch != null) {
        InsertDialog(
            book = b,
            chapter = ch,
            onDismiss = { insertOpen = false },
            onSave = { q, a ->
                val chapters = b.chapters.toMutableList()
                chapters[ch.index] = chapters[ch.index].copy(notes = ch.notes + ("问：" + q) + ("答：" + a))
                val next = b.copy(chapters = chapters, updatedAt = System.currentTimeMillis())
                scope.launch {
                    withContext(Dispatchers.IO) { BookStore.save(next) }
                    book = next
                    onChanged()
                }
            },
        )
    }

    /* ---------------- 长按：倍速 / 清除朗读 ---------------- */
    if (speedOpen) {
        SpeedDialog(
            speed = speed,
            hasAudio = ch?.hasAudio == true,
            onChange = { v ->
                speed = v
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching { player.value?.playbackParams = PlaybackParams().setSpeed(v) }
                }
            },
            onClear = {
                val c = ch
                if (c != null) {
                    runCatching { File(c.audioPath).delete() }
                    val chapters = b.chapters.toMutableList()
                    chapters[c.index] = chapters[c.index].copy(audioPath = "", audioParts = emptyList())
                    val next = b.copy(chapters = chapters, updatedAt = System.currentTimeMillis())
                    scope.launch {
                        withContext(Dispatchers.IO) { BookStore.save(next) }
                        book = next
                        stop()
                        onChanged()
                    }
                }
                speedOpen = false
            },
            onDismiss = { speedOpen = false },
        )
    }
}

@Composable
private fun FlatButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun ChapterButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) MiuixTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
        )
    }
}

/* ---------------- 插入对话框 ---------------- */

@Composable
private fun InsertDialog(
    book: Book,
    chapter: BookChapter,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var a by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    OverlayCard {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("插入", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "跟 AI 聊，用来纠正文里的逻辑。这段对话**不会写进正文**。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))

            if (chapter.notes.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    chapter.notes.forEach {
                        Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(10.dp),
            ) {
                BasicTextField(
                    value = q,
                    onValueChange = { q = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                )
                if (q.isEmpty()) {
                    Text("比如：他上一章明明在城里，这一章怎么到山上了？", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }

            if (a.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(a, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onDismiss() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("关闭", fontSize = 13.sp) }

                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable(enabled = !busy && q.isNotBlank()) {
                            busy = true
                            scope.launch {
                                val r = BookAi.discuss(book, chapter, q)
                                a = r.getOrElse { "失败了：" + (it.message ?: "") }
                                if (r.isSuccess) onSave(q, a)
                                q = ""
                                busy = false
                            }
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "问着…" else "问 AI",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/* ---------------- 长按调速 ---------------- */

@Composable
private fun SpeedDialog(
    speed: Float,
    hasAudio: Boolean,
    onChange: (Float) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayCard {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("朗读设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("倍速 " + String.format("%.2f", speed) + "×", fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))

            val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                steps.take(5).forEach { s -> SpeedChip(s, speed, onChange) }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                steps.drop(5).forEach { s -> SpeedChip(s, speed, onChange) }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (hasAudio) MiuixTheme.colorScheme.error.copy(alpha = 0.14f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable(enabled = hasAudio) { onClear() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (hasAudio) "清除这一章的朗读" else "还没有朗读",
                    fontSize = 13.sp,
                    color = if (hasAudio) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onDismiss() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text("关闭", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun SpeedChip(value: Float, current: Float, onChange: (Float) -> Unit) {
    val on = kotlin.math.abs(value - current) < 0.01f
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (on) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceContainerHigh
            )
            .clickable { onChange(value) }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value.toString().removeSuffix(".0") + "×",
            fontSize = 11.5.sp,
            color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun OverlayCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 22.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) { content() }
    }
}
