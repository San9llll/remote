package lo.naui.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 功能页：这一版先放了「文件管理」。
 * 以后要加别的小工具，往下面再摞一张 GlassCard 就行。
 */
@Composable
fun ToolsScreen(
    onOpenFiles: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("功能", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "能用最高权限去摸系统的地方",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(20.dp))

        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "文件管理",
                    summary = "能一直往上翻到 / ，也可以自己加外部存储目录",
                    onClick = onOpenFiles,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
