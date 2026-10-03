package lo.naui.ui.settings

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
                if (floatDenied) {
                    Text(
                        "悬浮窗权限得手动给：系统设置 → 应用 → 显示在其他应用上层。开完回来再打开开关",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
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
