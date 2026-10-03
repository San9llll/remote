package lo.naui.ui.agent

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
                            Text(
                                c.title,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurface,
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
}
