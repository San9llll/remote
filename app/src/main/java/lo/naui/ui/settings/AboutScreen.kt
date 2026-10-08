package lo.naui.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import lo.naui.ui.component.GlassCard
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lo.naui.BuildConfig
import lo.naui.sys.UpdateChecker
import lo.naui.sys.UpdateInfo
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于 + 检查更新。
 *
 * 检查走 GitHub 的 releases/latest，**包在本应用里下**（不是点一下跳浏览器），
 * 下完用 FileProvider 直接拉起系统安装器。
 */
@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerOk by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun check() {
        if (checking) return
        checking = true
        banner = null
        scope.launch {
            UpdateChecker.check()
                .onSuccess { info ->
                    if (info.version.isBlank()) {
                        bannerOk = false
                        banner = "拿到的版本号是空的"
                    } else if (UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                        if (info.asset == null) {
                            bannerOk = false
                            banner = "有新版本 v" + info.version + "，但那个 release 里没有 apk"
                        } else {
                            found = info
                        }
                    } else {
                        bannerOk = true
                        banner = "已是最新版本 v" + BuildConfig.VERSION_NAME
                    }
                }
                .onFailure {
                    bannerOk = false
                    banner = "检查失败：" + (it.message ?: it.javaClass.simpleName)
                }
            checking = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 28.dp),
    ) {
        PageHeader(title = "关于", subtitle = "版本与更新", onBack = onBack)

        // 检查结果提示条
        banner?.let { msg ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (bannerOk) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MiuixTheme.colorScheme.error.copy(alpha = 0.14f)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    msg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (bannerOk) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- 顶部小卡片 ----
        //
        // 用户要的：Nakour + 版本号做成一张**液态玻璃**小卡片，
        // 卡片下面一行小字署名。
        //
        // 顺带把原来「开发者工具 → 重新走一遍准备界面」那个后门挪到这儿：
        // **连点版本号 5 次** = 重置首次标记并立刻回到准备界面。
        //（开发者工具整个分区已经删了，这是唯一入口，所以不做任何视觉提示）
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            var taps by remember { mutableStateOf(0) }

            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Nakour", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "v" + BuildConfig.VERSION_NAME,
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable {
                            taps++
                            if (taps < 5) return@clickable
                            taps = 0
                            lo.naui.ui.setup.SetupStore.init(ctx)
                            lo.naui.ui.setup.SetupStore.reset()
                            // 光重置标记不够 —— MainActivity 那个 setupDoneFlag 是
                            // remember 出来的一次性值，本进程内不会再读第二遍。
                            // 所以直接把 Activity 重建，onCreate 重跑就会走准备界面。
                            val act = findActivity(ctx)
                            if (act != null) {
                                act.recreate()
                            } else {
                                android.widget.Toast
                                    .makeText(ctx, "重置好了，重启 App 就能看到准备界面", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                    )
                }
            }

            Text(
                "Qwen x DeepSeek",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Section("更新")
        Group(index = 0) {
            ArrowPreference(
                title = if (checking) "正在检查…" else "检查更新",
                summary = "从 GitHub 上问有没有新版本，有的话直接在这里下",
                onClick = { check() },
            )
            // 下载进度
            progress?.let { p ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("正在下载 " + (p * 100).toInt() + "%", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        Section("版本")
        Group(index = 1) {
            Row2("应用版本", "v" + BuildConfig.VERSION_NAME)
            Row2("构建号", BuildConfig.VERSION_CODE.toString())
        }

        Section("界面")
        Group(index = 2) {
            Row2("外壳", "全景 · 大图 + 侧边栏")
            Row2("UI 库", "Miuix（同参考项目）")
            Row2("工具链", "AGP 9.3.1 / Kotlin 2.4.10")
        }
    }

    /* ---------------- 有新版本 ---------------- */
    found?.let { info ->
        val asset = info.asset ?: return@let
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(enabled = progress == null) { found = null },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 400.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("有新版本", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "v" + BuildConfig.VERSION_NAME + "  →  v" + info.version,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            info.notes.take(600),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        asset.name + " · " + (asset.size / 1024.0 / 1024.0).let { String.format("%.1f MB", it) },
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
                                .clickable(enabled = progress == null) { found = null }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("以后再说", fontSize = 13.sp)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.primary)
                                .clickable(enabled = progress == null) {
                                    progress = 0f
                                    scope.launch {
                                        UpdateChecker.download(ctx, asset) { p -> progress = p }
                                            .onSuccess { file ->
                                                progress = null
                                                found = null
                                                bannerOk = true
                                                banner = "下载完了，正在拉起安装器…"
                                                UpdateChecker.install(ctx, file).onFailure {
                                                    bannerOk = false
                                                    banner = "安装器拉不起来，apk 在 " + file.absolutePath
                                                }
                                            }
                                            .onFailure {
                                                progress = null
                                                bannerOk = false
                                                banner = "下载失败：" + (it.message ?: "")
                                                found = null
                                            }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (progress != null) "下载中…" else "下载并安装",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(
        t,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(index: Int = -1, content: @Composable () -> Unit) {
    // 和其它页一致：玻璃卡 + 入场
    GlassCard(
        backdrop = null,
        enterIndex = index,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun Row2(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/**
 * 从 Compose 给的 Context 里把 Activity 挖出来。
 *
 * LocalContext 拿到的常常是 ContextWrapper（主题包一层、Compose 再包一层），
 * 直接 `as Activity` 会 ClassCastException。⚠️ 也别用 `this` ——
 * composable 的 lambda 里 `this` 不是 Activity（这个坑项目里踩过）。
 */
private fun findActivity(c: android.content.Context): android.app.Activity? {
    var cur: android.content.Context = c
    while (true) {
        if (cur is android.app.Activity) return cur
        if (cur !is android.content.ContextWrapper) return null
        cur = cur.baseContext
    }
}
