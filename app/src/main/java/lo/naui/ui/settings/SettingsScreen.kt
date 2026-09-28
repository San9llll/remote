package lo.naui.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import lo.naui.ui.common.SectionTitle
import lo.naui.ui.theme.ThemePrefs
import lo.naui.ui.theme.label
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 设置页 —— 只留两块：外观 / 关于。
 *
 * 所有跟长相有关的（配色、明暗、密度、布局、开关图标、跟随壁纸）都收在「主题」那一页里，
 * 点进去一页管完，免得主列表被散项撑乱。
 */
@Composable
fun SettingsScreen(
    prefs: ThemePrefs,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item(key = "theme") {
                SectionTitle("外观")
                SettingsCard {
                    ArrowPreference(
                        title = "主题",
                        summary = prefs.paletteStyle.label() + " · " + prefs.darkMode.label +
                            " · " + prefs.globalLayout.label,
                        startAction = { SettingsIcon(MiuixIcons.Layers) },
                        onClick = onOpenTheme,
                    )
                }
            }

            item(key = "about") {
                SectionTitle("关于")
                SettingsCard {
                    ArrowPreference(
                        title = "关于 Nakour",
                        summary = "版本 v" + lo.naui.BuildConfig.VERSION_NAME,
                        startAction = { SettingsIcon(MiuixIcons.Notes) },
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 12.dp),
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column { content() }
    }
}
