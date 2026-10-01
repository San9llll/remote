package lo.naui.ui.settings

import androidx.compose.foundation.layout.Column
import lo.naui.ui.component.GlassCard
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lo.naui.ui.common.Option
import lo.naui.ui.common.OptionDialog
import lo.naui.ui.common.PageHeader
import lo.naui.ui.common.SectionTitle
import lo.naui.ui.component.IndicatorSwitchPreference
import lo.naui.ui.theme.INFO_LINE_COUNT
import lo.naui.ui.theme.InfoMetric
import lo.naui.ui.theme.ThemePrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 侧栏设置（主题 →「导航与外壳」里进来）。
 *
 * 两块：
 *  ① 侧栏模块 —— 电量 / 信息 / 概览入口 显示不显示
 *  ② 信息内容 —— 信息块那 5 行分别放什么
 *
 * root 不在这里，它是全局的：真需要读受限节点时自己会用，不给开关。
 */
@Composable
fun RailSettingsScreen(prefs: ThemePrefs, onBack: () -> Unit = {}) {
    // 正在编辑第几行（-1 = 没弹）
    var editing by remember { mutableStateOf(-1) }
    val lines = prefs.railInfoList()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        PageHeader(title = "侧栏设置", subtitle = "侧栏模块 · 信息内容", onBack = onBack)

        SectionTitle("侧栏模块")
        RailCard(index = 0) {
            IndicatorSwitchPreference(
                checked = prefs.railShowBattery,
                onCheckedChange = { prefs.updateRailShowBattery(it) },
                title = "电量",
                summary = "导轨上那颗电池和百分比",
            )
            IndicatorSwitchPreference(
                checked = prefs.railShowInfo,
                onCheckedChange = { prefs.updateRailShowInfo(it) },
                title = "信息",
                summary = "电量下面那块信息，上下各一条细线标出范围",
            )
            IndicatorSwitchPreference(
                checked = prefs.railShowOverview,
                onCheckedChange = { prefs.updateRailShowOverview(it) },
                title = "概览入口",
                summary = "关掉之后侧栏里就不出现「概览」那一项",
            )
        }

        SectionTitle("信息内容（最多 " + INFO_LINE_COUNT + " 行）")
        RailCard(index = 1) {
            lines.forEachIndexed { i, m ->
                ArrowPreference(
                    title = "第 " + (i + 1) + " 行",
                    summary = if (m == InfoMetric.None) "不显示" else m.label + " · " + m.summary,
                    onClick = { editing = i },
                )
            }
        }

    }

    if (editing >= 0) {
        val current = lines.getOrNull(editing) ?: InfoMetric.None
        OptionDialog(
            show = true,
            title = "第 " + (editing + 1) + " 行",
            options = InfoMetric.entries.map { Option(it.id, it.label, it.summary) },
            currentId = current.id,
            onPick = { id -> prefs.updateRailInfoAt(editing, InfoMetric.of(id)) },
            onDismiss = { editing = -1 },
        )
    }
}

@Composable
private fun RailCard(index: Int = -1, content: @Composable () -> Unit) {
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
