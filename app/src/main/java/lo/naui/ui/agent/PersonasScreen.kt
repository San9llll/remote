package lo.naui.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.agent.AgentStore
import lo.naui.agent.Persona
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 人格单独一页。
 *
 * 列表能切、能新建、能删（至少留一个），点进去改名字和提示词。
 * 出厂带一个「雫」，用户可以把提示词清空当成"不设人格"用。
 */
@Composable
fun PersonasScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AgentStore.init(ctx)

    var editing by remember { mutableStateOf<Persona?>(null) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "人格",
            subtitle = "现在用的是「" + AgentStore.persona.name + "」",
            onBack = onBack,
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            AgentStore.personas.forEach { p ->
                val on = p.id == AgentStore.personaId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { AgentStore.selectPersona(p.id) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (on) "● " else "○ ") + p.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (p.prompt.isBlank()) "（没写提示词）"
                            else p.prompt.replace('\n', ' ').take(40) + if (p.prompt.length > 40) "…" else "",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { editing = p }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text("编辑", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable { editing = Persona(Persona.newId(), "新人格", "") }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋ 新建人格", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    editing?.let { p ->
        PersonaEditor(
            initial = p,
            onDismiss = { editing = null },
            onSave = { AgentStore.savePersona(it); editing = null },
            onDelete = { AgentStore.deletePersona(p.id); editing = null },
            canDelete = AgentStore.personas.size > 1,
        )
    }
}

@Composable
private fun PersonaEditor(
    initial: Persona,
    onDismiss: () -> Unit,
    onSave: (Persona) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surface)
                .clickable(enabled = false) { }
                .padding(18.dp),
        ) {
            Text("人格", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            Text("名字", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("提示词（留空 = 不设人格）", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canDelete) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.14f))
                            .clickable { onDelete() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("删掉", fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onDismiss() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("取消", fontSize = 13.sp) }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable {
                            onSave(initial.copy(name = name.trim().ifBlank { "没名字" }, prompt = prompt))
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("保存", fontSize = 13.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
