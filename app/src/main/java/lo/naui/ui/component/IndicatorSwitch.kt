// Adapted from Aster (LyraVoid/Aster, GPL-3.0) —— 与 Miuix 的 Switch 几何保持一致。
package lo.naui.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import lo.naui.ui.theme.Prefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchColors
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开关拇指里要不要放个状态图标。
 *
 * 对齐 Aster 的 SwitchIndicator，但存储换成我们自己的 SharedPreferences，
 * 这样外观那一行改完，后面所有页面当场生效。
 */
object SwitchIndicator {
    /** 强制打开，主题页里那个「开关」分区已经删了 */
    val enabled: Boolean get() = true

    fun update(value: Boolean) {
        // 强制打开，没得改；留着这个签名免得别处调用报错
    }
}

// Miuix 的 Switch 是拿这几个数画出来的，而且一个都没暴露出来，所以照抄一遍：
// 49x28 dp 的轨道，20 dp 的拇指，关时左起 4 dp、开时 25 dp，拇指垂直居中。
private val TrackHeight = 28.dp
private val ThumbSize = 20.dp
private val ThumbOff = 4.dp
private val ThumbOn = 25.dp
private val IndicatorSize = 12.dp

/**
 * 拇指带状态图标的 Miuix [Switch]。
 *
 * Miuix 的开关没有给拇指留插槽，所以开关本身还是 Miuix 画的，
 * 图标是叠在它拇指位置上的一层。改这里之前先看懂上面那组几何数字。
 */
@Composable
fun IndicatorSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    colors: SwitchColors = SwitchDefaults.switchColors(),
    enabled: Boolean = true,
) {
    Box(modifier = modifier) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = colors,
            enabled = enabled,
        )
        if (SwitchIndicator.enabled) {
            val thumbLeft by animateDpAsState(
                targetValue = if (checked) ThumbOn else ThumbOff,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 987f),
            )
            // 图标用它所处轨道的颜色来画，这样两种拇指上都清楚，不用再养第二种颜色。
            val tint = when {
                checked && enabled -> MiuixTheme.colorScheme.primary
                checked -> MiuixTheme.colorScheme.disabledPrimary
                enabled -> MiuixTheme.colorScheme.secondary
                else -> MiuixTheme.colorScheme.disabledSecondary
            }
            Icon(
                imageVector = if (checked) MiuixIcons.Ok else MiuixIcons.Close,
                contentDescription = null,
                modifier = Modifier
                    .offset(
                        x = thumbLeft + (ThumbSize - IndicatorSize) / 2,
                        y = (TrackHeight - IndicatorSize) / 2,
                    )
                    .size(IndicatorSize),
                tint = tint,
            )
        }
    }
}

/**
 * 把 Miuix 的 `SwitchPreference` 里的开关换成 [IndicatorSwitch]。
 *
 * Miuix 那行没给开关留插槽，所以整行按 Miuix 的样子重画一遍；
 * 除了开关本身，连行尾动作和 `Role.Switch` 的读屏方式都还是 Miuix 自己的排法。
 */
@Composable
@NonRestartableComposable
fun IndicatorSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    switchColors: SwitchColors = SwitchDefaults.switchColors(),
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    BasicComponent(
        modifier = modifier,
        insideMargin = insideMargin,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
            ) {
                endActions()
            }
            IndicatorSwitch(
                checked = checked,
                onCheckedChange = currentOnCheckedChange,
                enabled = enabled,
                colors = switchColors,
            )
        },
        bottomAction = bottomAction,
        onClick = {
            currentOnCheckedChange.takeIf { enabled }?.invoke(!checked)
        },
        role = Role.Switch,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}
