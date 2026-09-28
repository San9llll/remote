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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一张通用的内容页：大标题 + 副标题 + 一摞玻璃卡。
 *
 * 占位用。要接真东西的时候，把调用它的那页换成自己的 Composable 就行，
 * 这一页的排版（14dp 边距、玻璃卡 contentPadding 18dp）和参考项目一致。
 */
@Composable
fun SimplePage(
    title: String,
    subtitle: String,
    items: List<Pair<String, String>>,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(20.dp))

        items.forEach { (head, body) ->
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                contentPadding = 18.dp,
            ) {
                Column {
                    Text(head, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        body,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
