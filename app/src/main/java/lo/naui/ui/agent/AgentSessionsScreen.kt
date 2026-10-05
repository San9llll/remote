package lo.naui.ui.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lo.naui.agent.AgentStore
import lo.naui.agent.ChatDb
import lo.naui.agent.Conversation
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 会话列表。全在本地，一个会话一个文件。
 * 点一条就切过去，右边那个 ✕ 是本机删掉（不动别处）。
 */
@Composable
fun AgentSessionsScreen(onBack: () -> Unit = {}) {
    val rctx = androidx.compose.ui.platform.LocalContext.current
    // 正在改名的那个会话
    var renaming by remember { mutableStateOf<lo.naui.agent.Conversation?>(null) }
    // 改完让它重画
    var tick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    ChatDb.init(ctx)

    var items by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        items = withContext(Dispatchers.IO) { ChatDb.list() }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "会话",
            subtitle = "都存在本机",
            action = "新建",
            onAction = {
                AgentStore.beginNewConv()
                onBack()
            },
            onBack = onBack,
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            if (items.isEmpty()) {
                item(key = "__empty__") {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("还没有会话", fontSize = 14.sp)
                            Text(
                                "发第一条消息就会自己建一个",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            items(items, key = { it.id }) { c ->
                val active = AgentStore.activeConvId == c.id
                Card(
                    Modifier
                        // animateItem：进来/出去/换位置时自带淡入和位移
                        .animateItem()
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp)
                        .clickable {
                            AgentStore.openConv(c.id)
                            onBack()
                        }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            // 标题点了就改名（用户要的"会话标题可修改"）。
                            // 这个 clickable 在 Text 上，会吃掉点击 —— 不会连带把会话打开。
                            Text(
                                c.title,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { renaming = c }
                                    .padding(vertical = 2.dp, horizontal = 2.dp),
                            )
                            Text(
                                if (c.updatedAt > 0) stamp.format(Date(c.updatedAt)) else "还没聊过",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            "✕",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ChatDb.delete(c.id) }
                                        if (AgentStore.activeConvId == c.id) {
                                            AgentStore.beginNewConv()
                                        }
                                        tick++
                                    }
                                }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    // 改名弹窗
    renaming?.let { conv ->
        RenameDialog(
            current = conv.title,
            onDismiss = { renaming = null },
            onSave = { newName ->
                lo.naui.agent.ChatDb.rename(conv.id, newName)
                renaming = null
                tick++
            },
        )
    }
    @Suppress("UNUSED_EXPRESSION")
    run { tick }

}


/**
 * 改会话标题的弹窗。
 *
 * 用户要求"会话标题可修改" —— 自动生成的是取第一条消息的头几个字，
 * 经常认不出是哪次聊的。
 */
@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surface)
                .clickable(enabled = false) { }
                .padding(18.dp),
        ) {
            Text("改个名字", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.5.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MiuixTheme.colorScheme.primary
                    ),
                )
                if (text.isEmpty()) {
                    Text(
                        "给这次对话起个名",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onDismiss() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("算了", fontSize = 13.sp) }

                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable { onSave(text) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("保存", fontSize = 13.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
