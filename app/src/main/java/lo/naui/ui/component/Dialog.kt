// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 关闭按钮的兜底文案 */
private const val DefaultConfirm = "确定"
private const val DefaultDismiss = "取消"

/**
 * 确认弹窗 —— 尺寸照 Aster 的 ConfirmDialog：
 * 320dp 宽、28dp 圆角、24dp 内边距，两个按钮各占一半。
 */
@Composable
fun ConfirmDialog(
    title: String,
    content: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = DefaultConfirm,
    dismissText: String = DefaultDismiss,
) {
    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOff,
        ),
    ) {
        Card(
            modifier = Modifier.width(320.dp).wrapContentHeight(),
            cornerRadius = 28.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    textAlign = TextAlign.Center,
                )
                if (content.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = content,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = dismissText,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/** 转圈弹窗（Aster 的 LoadingDialog：100dp 方卡、24dp 圆角） */
@Composable
fun LoadingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Card(
            modifier = Modifier.size(100.dp),
            cornerRadius = 24.dp,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * 确认弹窗的简易句柄 —— Aster 那套是 suspend 的，这里用状态实现，
 * 用法：`val confirm = rememberConfirm(); ...; confirm.ask { 真要执行的事 } `
 */
class ConfirmHandle internal constructor(
    private val onState: (Boolean) -> Unit,
    private val meta: MutableConfirmMeta,
) {
    var visible by mutableStateOf(false)
        private set

    fun ask(block: () -> Unit) {
        meta.block = block
        visible = true
        onState(true)
    }

    fun hide() {
        visible = false
        onState(false)
    }

    internal fun run() {
        meta.block?.invoke()
        hide()
    }
}

internal class MutableConfirmMeta {
    var title: String = DefaultConfirm
    var content: String = ""
    var block: (() -> Unit)? = null
}

@Composable
fun rememberConfirm(
    title: String = "确认操作",
    content: String = "",
): ConfirmHandle {
    val meta = remember { MutableConfirmMeta().also { it.title = title; it.content = content } }
    var state by remember { mutableStateOf(false) }
    val handle = remember(meta) { ConfirmHandle({ state = it }, meta) }
    if (state) {
        ConfirmDialog(
            title = meta.title,
            content = meta.content,
            onConfirm = { handle.run() },
            onDismiss = { handle.hide() },
        )
    }
    return handle
}
