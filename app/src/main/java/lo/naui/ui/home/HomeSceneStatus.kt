// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页的状态条 —— 对齐 Aster 的 SceneStatusStrip：
 * 两列并排，中间一条 1dp 宽、30dp 高的竖线，列的标题全大写走 footnote1、值走 body1。
 */
@Composable
fun SceneStatusStrip(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneStatusColumn(
            title = leftTitle,
            value = leftValue,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .padding(horizontal = 14.dp)
                .width(1.dp)
                .height(30.dp)
                .background(MiuixTheme.colorScheme.dividerLine)
        )
        SceneStatusColumn(
            title = rightTitle,
            value = rightValue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SceneStatusColumn(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = title.uppercase(),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
