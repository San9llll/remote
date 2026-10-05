package lo.naui.ui.pages

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
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
 * 功能页。要加新工具就往下面再摞一张玻璃卡。
 *
 * 这些页都能用「音量上 + 音量下 同时按」钉到左边快捷栏。
 */
@Composable
fun ToolsScreen(
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenShelf: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    // 左边一条侧边栏（工具入口 + 实时状态），右边是原来的卡片列表
    Row(Modifier.fillMaxSize()) {

        ToolsRail(
            onOpenFiles = onOpenFiles,
            onOpenTerminal = onOpenTerminal,
            onOpenShelf = onOpenShelf,
        )

    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("功能", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "能用最高权限去摸系统的地方 · 进子页后按音量上+下可钉到侧栏",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(20.dp))

        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 6.dp,
            enterIndex = 0,
        ) {
            Column(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "文件管理",
                    summary = "双栏 · 能一直往上翻到 /，也可以自己加外部存储目录",
                    onClick = onOpenFiles,
                )
                ArrowPreference(
                    title = "终端",
                    summary = "按当前最高权限执行命令（root / Shizuku / 本地 sh）",
                    onClick = onOpenTerminal,
                )
                ArrowPreference(
                    title = "雫的书柜",
                    summary = "写小说 · AI 续写 · 分角色配音朗读",
                    onClick = onOpenShelf,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
    }
}
