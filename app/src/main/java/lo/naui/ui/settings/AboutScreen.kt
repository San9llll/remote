package lo.naui.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 24.dp),
    ) {
        PageHeader(title = "关于", subtitle = "版本与信息", onBack = onBack)

        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Nakour", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "v" + lo.naui.BuildConfig.VERSION_NAME,
                fontSize = 12.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Section("版本")
        Group {
            Row2("应用版本", "v" + lo.naui.BuildConfig.VERSION_NAME)
            Row2("构建号", lo.naui.BuildConfig.VERSION_CODE.toString())
        }

        Section("界面")
        Group {
            Row2("外壳", "全景 · 大图 + 侧边栏")
            Row2("UI 库", "Miuix（同参考项目）")
            Row2("工具链", "AGP 9.3.1 / Kotlin 2.4.10")
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
private fun Group(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column { content() } }
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
