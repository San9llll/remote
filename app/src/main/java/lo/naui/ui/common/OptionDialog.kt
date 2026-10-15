// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.common

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

/** 一个选项：id、显示名、说明 */
data class Option(val id: String, val label: String, val summary: String = "")

/**
 * 单选弹窗 —— 对齐 Aster 里用 OverlayDialog 装 RadioButtonPreference 的做法。
 *
 * 之前设置页是用「点一下循环切换」糊弄的，Aster 是正经弹一个列表出来让人挑。
 */
@Composable
fun OptionDialog(
    show: Boolean,
    title: String,
    options: List<Option>,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        options.forEach { opt ->
            RadioButtonPreference(
                title = opt.label,
                summary = opt.summary,
                selected = opt.id == currentId,
                onClick = {
                    onPick(opt.id)
                    onDismiss()
                },
            )
        }
    }
}
