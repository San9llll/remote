package lo.naui.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.common.SectionTitle
import lo.naui.ui.component.GlassCard
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
                SettingsCard {
                    ArrowPreference(
                        title = "主题",
                        summary = prefs.paletteStyle.label() + " · " + prefs.darkMode.label +
                            " · " + prefs.globalLayout.label,
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
private fun SettingsCard(content: @Composable () -> Unit) {
    // 和其它页一样：玻璃卡，圆角吃主题里那个参数
    GlassCard(
        backdrop = null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}
