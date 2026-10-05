package lo.naui.ui.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.common.SectionTitle
import lo.naui.ui.component.GlassCard
import lo.naui.ui.component.IndicatorSwitchPreference
import lo.naui.ui.theme.ThemePrefs
import lo.naui.ui.theme.label
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页。
 *
 * 原来用 Miuix 的 `Scaffold` + `TopAppBar` 搭的，问题是 **Scaffold 会自己铺一层
 * containerColor**，把外壳画在底层的那张「设置页背景」整个盖住 —— 看着就像
 * "背景设置了没反应 / 卡片发灰"，这就是那个"外观有问题"。
 *
 * 现在改成和其它页一样的写法：自己画标题 + 一个 LazyColumn，背景直接透出来。
 * 分组、间距、卡片的观感和展开动画都跟原来一致。
 */
@Composable
fun SettingsScreen(
    prefs: ThemePrefs,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    lo.naui.agent.AgentTaskStore.init(ctx)
    lo.naui.sys.FloatingBall.init(ctx)
    lo.naui.sys.FluidCloud.init(ctx)

    // 权限被拒的时候给一句提示（开关状态本身是可观察的，不用手动刷新）
    var floatDenied by remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // 顶栏（跟原来 TopAppBar 的位置和字号对齐）
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Text("设置", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
        ) {
            item(key = "theme") {
                SectionTitle("外观")
                SettingsCard(index = 0) {
                    ArrowPreference(
                        title = "主题",
                        summary = prefs.paletteStyle.label() + " · " + prefs.darkMode.label +
                            " · " + prefs.globalLayout.label,
                        onClick = onOpenTheme,
                    )
                }
            }

            item(key = "keepalive") {
                SectionTitle("后台")
                SettingsCard(index = 1) {
                    IndicatorSwitchPreference(
                        checked = lo.naui.agent.AgentTaskStore.keepAlive,
                        onCheckedChange = { lo.naui.agent.AgentTaskStore.updateKeepAlive(it) },
                        title = "后台留存",
                        summary = "任务丢给前台服务跑，切页 / 切后台都不容易断",
                    )
                    IndicatorSwitchPreference(
                        checked = lo.naui.agent.AgentTaskStore.bootStart,
                        onCheckedChange = { lo.naui.agent.AgentTaskStore.updateBootStart(it) },
                        title = "开机自启",
                        summary = "开机后把 Agent 服务拉起来待命",
                    )
                    ArrowPreference(
                        title = "把它加进电池白名单",
                        summary = "系统省电策略有时还是会杀，加进白名单更稳",
                        onClick = { requestIgnoreBattery(ctx) },
                    )
                }
            }

            item(key = "connect") {
                SectionTitle("连接")
                SettingsCard(index = 3) {
                    IndicatorSwitchPreference(
                        checked = lo.naui.sys.FloatingBall.enabled,
                        onCheckedChange = { v ->
                            if (v && !lo.naui.sys.FloatingBall.canShow(ctx)) {
                                // 没权限就先把开关弹回去，然后引它去开
                                lo.naui.sys.FloatingBall.setEnabled(ctx, false)
                                floatDenied = true
                                lo.naui.sys.FloatingBall.requestPermission(ctx)
                            } else {
                                lo.naui.sys.FloatingBall.setEnabled(ctx, v)
                            }
                        },
                        title = "系统悬浮窗",
                        summary = "飘一颗小球在别的应用上面，看 Agent 跑到哪了；点一下回 App",
                    )
                    IndicatorSwitchPreference(
                        checked = lo.naui.sys.FluidCloud.enabled,
                        onCheckedChange = { lo.naui.sys.FluidCloud.setEnabled(ctx, it) },
                        title = "流体云",
                        summary = if (lo.naui.sys.FluidCloud.supported())
                            "任务进度抬到状态栏那块小胶囊（" +
                                lo.naui.sys.FluidCloud.systemLabel() + "）"
                        else
                            "当前系统不认这套字段，开了也只有普通通知",
                    )
                }
                // ---- hook 点自动适配（LSPosed）----
                HookProbeCard()

                if (lo.naui.sys.FluidCloud.enabled) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { lo.naui.sys.FluidCloud.sendTest(ctx) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column {
                            Text("测试一下", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                lo.naui.sys.FluidCloud.diagnose(ctx),
                                fontSize = 10.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                if (floatDenied) {
                    Text(
                        "悬浮窗权限得手动给：系统设置 → 应用 → 显示在其他应用上层。开完回来再打开开关",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }

            item(key = "build") {
                SectionTitle("编译")
                BuildCard()
            }

            item(key = "perf") {
                SectionTitle("性能")
                SettingsCard(index = 4) {
                    IndicatorSwitchPreference(
                        checked = prefs.powerSave,
                        onCheckedChange = { prefs.updatePowerSave(it) },
                        title = "省电模式",
                        summary = "关掉所有模糊和真折射、切背景不播涟漪、卡片不做入场动画。" +
                            "手机发烫、或者 CPU 被系统压得很低的时候开它试试",
                    )
                }
            }

            // ---- 依赖 ----
            // 用户要的：在后台终端里下编译 APK 要的包
            item(key = "deps") {
                SectionTitle("依赖")
                BuildDepsCard()
            }

            // ---- 开发者工具 ----
            // 用户要的：在已有数据的情况下，把"只有第一次进门才会出现"的
            // 界面再触发一遍。主要就是准备引导那套。
            item(key = "devtools") {
                SectionTitle("开发者工具")
                DevToolsCard()
            }

            item(key = "about") {
                SectionTitle("关于")
                SettingsCard(index = 2) {
                    ArrowPreference(
                        title = "关于 Nakour",
                        summary = "版本 v" + lo.naui.BuildConfig.VERSION_NAME,
                        onClick = onOpenAbout,
                    )
                }
            }

            item(key = "tail") {
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(index: Int = -1, content: @Composable () -> Unit) {
    // 和其它页一样：玻璃卡，圆角吃主题里那个参数
    GlassCard(
        backdrop = null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        enterIndex = index,
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 跳系统那个「不优化电池」的页面 */
private fun requestIgnoreBattery(ctx: android.content.Context) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + ctx.packageName),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } else {
            val i = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }.onFailure {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * hook 点自动适配的卡片。
 *
 * 这套东西的来龙去脉：
 *   - 侧边栏是系统的 View，要挂进去只能走 LSPosed
 *   - 但侧边栏的类名每个系统版本都不一样，靠人逆太累
 *   - 所以让 Xposed 那边自己扫一遍，把像侧边栏的类挑出来
 *   - 扫描进度写在 /sdcard/Nakour/hook_probe.json，这边读出来画条
 *
 * 所以这个进度是**真的**，不是装样子。
 */
@Composable
private fun HookProbeCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var state by remember { androidx.compose.runtime.mutableStateOf(lo.naui.sys.ProbeState()) }
    var status by remember { androidx.compose.runtime.mutableStateOf("") }
    var picked by remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
    var alive by remember { androidx.compose.runtime.mutableStateOf(false) }

    // 每 800ms 刷一次进度 —— 扫描时看着条子动，心里有底
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val st = lo.naui.sys.HookProbeReader.read()
                state = st
                lo.naui.sys.HookProbeReader.cache(st)
                status = lo.naui.sys.HookProbeReader.status(ctx)
                picked = lo.naui.sys.HookProbeReader.picked()
                alive = lo.naui.sys.XposedActive.isActive(ctx)
            }
            kotlinx.coroutines.delay(800)
        }
    }

    GlassCard(
        backdrop = null,
        enterIndex = 4,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("挂进系统侧边栏", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (alive) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (alive) "LSPosed 已生效" else "未检测到",
                        fontSize = 10.5.sp,
                        color = if (alive) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "走 LSPosed 把自己挂进侧边栏 —— 比悬浮窗更贴系统。" +
                    "侧边栏的类名每个系统版本都不一样，所以这里是**自动扫**的。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(12.dp))

            // ---- 进度条（真实的）----
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
                    val w = size.width * state.progress.coerceIn(0f, 1f)
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

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.stage.ifBlank { status },
                    fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (state.progress * 100).toInt().toString() + "%",
                    fontSize = 10.5.sp,
                    color = if (state.done) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 挑出来的候选，列前几个
            if (picked.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp),
                ) {
                    Text("挑出的候选", fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    picked.take(5).forEach { n ->
                        Text(
                            n,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                    if (picked.size > 5) {
                        Text(
                            "…还有 " + (picked.size - 5) + " 个",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            scope.launch { lo.naui.sys.HookProbeReader.requestRescan() }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("重新适配", fontSize = 12.5.sp)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(android.net.Uri.parse("package:" + ctx.packageName))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("怎么启用", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}
