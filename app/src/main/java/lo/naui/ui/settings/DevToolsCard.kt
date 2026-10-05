package lo.naui.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开发者工具。
 *
 * ## 为什么要有这个
 *
 * 用户的原话：**"主要是为了在已有使用数据的情况下触发一些只有第一次进入
 * 才能触发的界面"**。
 *
 * 有些界面设计上"一辈子只见一次"（比如准备引导、首次配置向导），
 * 但它们恰恰是最容易写错、最需要反复看的东西。
 * 装一次 App 只能看一次，改一版要重装一遍 —— 太慢了。
 *
 * 所以把那些"第一次"标记摊到这儿，点一下就能重置，立刻能再看一遍。
 */
@Composable
fun DevToolsCard() {
    val ctx = LocalContext.current

    lo.naui.ui.setup.SetupStore.init(ctx)

    // 二次确认用
    var confirm by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {

        Text(
            "这里的东西是给调界面用的，平时不用碰。",
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp),
        )

        // ---- 准备界面 ----
        DevRow(
            title = "重新走一遍准备界面",
            summary = if (lo.naui.ui.setup.SetupStore.shouldShow)
                "现在就是这个状态（还没走完）"
            else "已经走完了 · 点一下重置，下次打开 App 会重新出现",
            emphasis = true,
        ) {
            lo.naui.ui.setup.SetupStore.reset()
            toast = "重置好了。**重启 App** 就能看到准备界面"
        }

        // ---- 走到一半 ----
        DevRow(
            title = "重置到某个中间步骤",
            summary = "当前记在：第 " + (lo.naui.ui.setup.SetupStore.lastStep + 1) + " 步",
        ) {
            lo.naui.ui.setup.SetupStore.reset()
            toast = "重置好了"
        }

        // ---- Agent 配置 ----
        DevRow(
            title = "清掉 Agent 配置",
            summary = "Key / 模型 / 服务商都会清空（会话记录不动）",
        ) {
            confirm = "agent"
        }

        // ---- 主题 ----
        DevRow(
            title = "主题恢复默认",
            summary = "玻璃参数、背景样式、配色都回到出厂那套",
        ) {
            confirm = "theme"
        }

        // ---- 全部 ----
        DevRow(
            title = "清空全部数据",
            summary = "会话、书、设置 —— 全没了。**慎点**",
        ) {
            confirm = "all"
        }

        toast?.let {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(10.dp),
            ) {
                Text(
                    it,
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }

    // ---- 二次确认 ----
    confirm?.let { what ->
        val (title, desc) = when (what) {
            "agent" -> "清掉 Agent 配置？" to "Key 和模型会没，会话记录留着"
            "theme" -> "主题恢复默认？" to "玻璃参数和背景会回到出厂设置"
            else -> "清空全部数据？" to "会话、书、设置全删，**没法恢复**"
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.error.copy(alpha = 0.10f))
                .padding(12.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    desc,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable { confirm = null }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("算了", fontSize = 12.sp) }

                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.error)
                            .clickable {
                                when (what) {
                                    "agent" -> {
                                        lo.naui.agent.AgentStore.updateApiKey("")
                                        lo.naui.agent.AgentStore.updateModel("")
                                        toast = "Agent 配置清了"
                                    }
                                    "theme" -> {
                                        lo.naui.ui.theme.Prefs.current?.resetToDefaults()
                                        toast = "主题恢复默认了"
                                    }
                                    else -> {
                                        runCatching { ctx.filesDir.deleteRecursively() }
                                        toast = "全清了。重启 App 生效"
                                    }
                                }
                                confirm = null
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // ⚠️ 不确定 MiuixTheme 有没有 onError，
                        // 用能确定的 onPrimary 就行 —— 底色是 error，白字也看得清
                        Text("确定", fontSize = 12.sp, color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DevRow(
    title: String,
    summary: String,
    emphasis: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (emphasis) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MiuixTheme.colorScheme.surfaceContainerHigh
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (emphasis) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                summary,
                fontSize = 10.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
