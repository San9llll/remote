package lo.naui.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import lo.naui.sys.Privilege
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 功能页的侧边栏。
 *
 * ## 为什么单独做一个
 *
 * 主页左边那条是"场景导轨"（时钟 / 电量 / 设备信息），
 * 跟"干活"没关系。功能页要的是**能直接点到工具**的那种侧边栏：
 *
 * ```
 * ┌──────┬──────────────────────┐
 * │ 文件 │  功能                 │
 * │ 终端 │  卡片…                │
 * │ 书柜 │                       │
 * │      │                       │
 * │ ──── │                       │
 * │ 权限 │                       │
 * │ root │                       │
 * └──────┴──────────────────────┘
 * ```
 *
 * 上面是工具入口，底下挂一条实时状态（权限级别 / Agent 在不在跑）。
 */
@Composable
fun ToolsRail(
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current

    // 底下的状态是活的 —— 权限可能中途变（刚给 Shizuku 授权），
    // Agent 也可能随时开跑
    var privLabel by remember { mutableStateOf("…") }
    var agentRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            privLabel = runCatching { Privilege.level(ctx).label }.getOrDefault("普通")
            agentRunning = lo.naui.agent.AgentTaskStore.runningCount > 0
            delay(3000)
        }
    }

    Column(
        modifier
            .width(74.dp)
            .fillMaxHeight()
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- 工具入口 ----
        RailButton("文件", "🗂", onOpenFiles)
        Spacer(Modifier.height(6.dp))
        RailButton("终端", "⌨", onOpenTerminal)
        Spacer(Modifier.height(6.dp))
        RailButton("书柜", "📚", onOpenShelf)

        Spacer(Modifier.weight(1f))

        // ---- 底部状态 ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 权限级别
            Text(
                "权限",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                privLabel,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = if (privLabel.contains("普通")) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                },
            )

            if (agentRunning) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "在跑",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 侧边栏上的一颗按钮 */
@Composable
private fun RailButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 19.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}
