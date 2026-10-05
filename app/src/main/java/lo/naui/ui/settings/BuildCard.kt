package lo.naui.ui.settings

import androidx.compose.foundation.layout.width
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import lo.naui.sys.Builder
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内置 GitHub 构建。
 *
 * ## 为什么是"调 GitHub"而不是"本机编译"
 *
 * Android 上**真编不了** —— 没有 JDK、没有 aapt2、没有 d8，
 * 这些东西加起来几百 MB，搬进手机不现实。
 *
 * 所以做成：**手机上点一下，GitHub 那边编，编完直接下回来装上**。
 * 用户要的效果是"不用开电脑就能出新包"，这就达到了。
 *
 * ## 要什么
 *
 * 一个 GitHub 细粒度 PAT，权限勾两个就够：
 *   · `Actions: Read and write`   ← 触发构建、查状态
 *   · `Contents: Read and write`  ← 下 Release（打 tag 需要 write）
 */
@Composable
fun BuildCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Builder.init(ctx)

    var tokenDraft by remember { mutableStateOf(Builder.token) }
    var tagDraft by remember { mutableStateOf("") }
    var run by remember { mutableStateOf<Builder.Run?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    var got by remember { mutableStateOf<String?>(null) }

    // 有 token 就每 8 秒刷一次状态（编一次要 2~5 分钟，8 秒够了）
    LaunchedEffect(Builder.token) {
        if (!Builder.ready) return@LaunchedEffect
        while (true) {
            runCatching { Builder.latest() }.getOrNull()?.let { run = it }
            kotlinx.coroutines.delay(8000)
        }
    }

    GlassCard(
        backdrop = null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("内置编译", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (Builder.ready) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (Builder.ready) "已配 Token" else "还没配",
                        fontSize = 10.5.sp,
                        color = if (Builder.ready) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "手机上点一下，让 GitHub 那边编，编完直接下回来。" +
                    "Android 本机真编不了（没 JDK），所以走这条路。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            // ---- Token ----
            Spacer(Modifier.height(12.dp))
            Text("GitHub Token", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    BasicTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    )
                    if (tokenDraft.isEmpty()) {
                        Text(
                            "github_pat_…（要 Actions 和 Contents 权限）",
                            fontSize = 11.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable {
                            Builder.updateToken(ctx, tokenDraft)
                            msg = "存好了"
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text("存", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.primary)
                }
            }

            // ---- Tag ----
            Spacer(Modifier.height(10.dp))
            Text("版本号（留空自动生成）", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    value = tagDraft,
                    onValueChange = { tagDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                )
                if (tagDraft.isEmpty()) {
                    Text(
                        "比如 v0.78 —— 填了就会出 Release",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ---- 状态 ----
            run?.let { r ->
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (!r.running && r.ok) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else if (!r.running) MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(10.dp),
                ) {
                    Column {
                        Text(
                            "最近一次：" + r.text,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                r.running -> MiuixTheme.colorScheme.onSurface
                                r.ok -> MiuixTheme.colorScheme.primary
                                else -> MiuixTheme.colorScheme.error
                            },
                        )
                        Text(
                            "#" + r.id + "  " + r.sha + "  " + r.branch,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // ---- 进度 ----
            if (progress >= 0f) {
                Spacer(Modifier.height(8.dp))
                val barColor = MiuixTheme.colorScheme.primary
                val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(trackColor),
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val w = size.width * progress.coerceIn(0f, 1f)
                        if (w > 0f) {
                            drawRoundRect(
                                color = barColor,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(w, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                            )
                        }
                    }
                }
                Text(
                    "下载中 " + (progress * 100).toInt() + "%",
                    fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            got?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "下好了：" + it,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            // ---- 按钮 ----
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (busy || !Builder.ready) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                            } else {
                                MiuixTheme.colorScheme.primary
                            }
                        )
                        .clickable(enabled = !busy && Builder.ready) {
                            busy = true
                            msg = "正在让 GitHub 开始编…"
                            scope.launch {
                                Builder.trigger(ctx, tagDraft.trim())
                                    .onSuccess { msg = "触发了，等它编（一般 2~5 分钟）" }
                                    .onFailure { msg = "触发失败：" + (it.message ?: "") }
                                busy = false
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "…" else "开始构建",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (busy || !Builder.ready) MiuixTheme.colorScheme.onSurfaceVariantSummary
                        else MiuixTheme.colorScheme.onPrimary,
                    )
                }

                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = !busy && Builder.ready) {
                            busy = true
                            progress = 0f
                            msg = "正在找最新的 APK…"
                            scope.launch {
                                val url = Builder.latestApkUrl()
                                if (url.isNullOrBlank()) {
                                    msg = "还没找到 APK（可能还没编好）"
                                    busy = false
                                    progress = -1f
                                    return@launch
                                }
                                msg = "开始下载"
                                Builder.downloadApk(ctx, url) { progress = it }
                                    .onSuccess {
                                        got = it.absolutePath
                                        msg = "下好了，去「关于」页装它"
                                        progress = -1f
                                    }
                                    .onFailure {
                                        msg = "下载失败：" + (it.message ?: "")
                                        progress = -1f
                                    }
                                busy = false
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("下载最新 APK", fontSize = 12.5.sp)
                }
            }
        }
    }
}
