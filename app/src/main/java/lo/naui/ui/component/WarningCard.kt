// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import lo.naui.ui.theme.LocalThemeModeState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class WarningCardTone {
    Warning,
    Neutral,
}

// 警示色往卡片自身的表面色里掺多少。深色表面会吞掉染色，所以要掺更多才看得出来。
private const val WarningCardTintLight = 0.16f
private const val WarningCardTintDark = 0.24f

/**
 * 警示卡的底色。
 *
 * 它是从卡片自己的表面色里混出来的，而不是直接取调色板里的错误容器色：
 * 2025 规范里那个容器是饱和的红，跟它配的文字色对比度只有 4.5:1，
 * 那是给徽章用的，不是给人读句子的。这样混出来的卡，警示感还在，
 * 字却仍是主题的正文色，实测浅色下 8:1 以上、深色下 10:1 以上。
 */
@Composable
private fun warningContainerColor(): Color = lerp(
    MiuixTheme.colorScheme.surfaceContainer,
    MiuixTheme.colorScheme.error,
    if (LocalThemeModeState.current.isDark) WarningCardTintDark else WarningCardTintLight,
)

@Composable
fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    tone: WarningCardTone = WarningCardTone.Warning,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val containerColor = when (tone) {
        WarningCardTone.Warning -> warningContainerColor()
        WarningCardTone.Neutral -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (tone) {
        WarningCardTone.Warning -> MiuixTheme.colorScheme.onSurfaceContainer
        WarningCardTone.Neutral -> MiuixTheme.colorScheme.onSurface
    }
    val accentColor = when (tone) {
        WarningCardTone.Warning -> MiuixTheme.colorScheme.error
        WarningCardTone.Neutral -> contentColor
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = contentColor,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
            } else {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = "关闭",
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
