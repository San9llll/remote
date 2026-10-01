package lo.naui.ui.agent

import android.content.Context
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import lo.naui.agent.AgentApi
import lo.naui.agent.AgentStore
import lo.naui.agent.PROVIDERS
import lo.naui.ui.common.BottomSheet
import lo.naui.ui.common.PageHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent 配置。
 *
 * 按用户要求重排：**Base URL / API Key / 模型全部收进「服务商」这一块**，
 * 换成"选一家就用它的"，除了自定义那家，Base URL 不再露出来。
 * 「生成参数」只留最大输出；人格挪去单独一页；「说明」整块删掉。
 */
@Composable
fun AgentConfigScreen(
    onBack: () -> Unit,
    onOpenPersonas: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AgentStore.init(ctx)

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val vendor = PROVIDERS.firstOrNull { it.id == AgentStore.providerId } ?: PROVIDERS.first()
    val isCustom = vendor.id == "custom"

    /** 外面那个选项 → 从下面滑出来的面板 */
    var sheetType by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "Agent 配置", subtitle = "服务商 · 生成参数 · 人格", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            /* ================= 服务商 ================= */
            Label("服务商")

            // 「现在使用的」——标题直接写当前那家
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        models = emptyList()
                        sheetType = "vendor"
                    }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("现在使用的", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        vendor.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("换 ›", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.primary)
            }

            // Base URL：只有自定义那家才露出来
            if (isCustom) {
                Spacer(Modifier.height(8.dp))
                Text("Base URL", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(4.dp))
                Input(AgentStore.baseUrl, "https://…") { AgentStore.updateBaseUrl(it) }
            }

            // API Key：左边标题、右边圆角输入栏，带隐藏
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Api Key",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(min = 78.dp),
                )
                Box(Modifier.weight(1f)) {
                    ApiKeyField(AgentStore.apiKey) { AgentStore.updateApiKey(it) }
                }
            }

            // 模型
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(min = 78.dp),
                )
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            models = emptyList()
                            sheetType = "model"
                        }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        AgentStore.model,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("选 ›", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.primary)
                }
            }

            /* ================= 生成参数 ================= */
            Spacer(Modifier.height(20.dp))
            Label("生成参数")

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { sheetType = "maxtoken" }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最大回复长度", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    if (AgentStore.maxTokens >= 1_000_000) "不限（默认）"
                    else AgentStore.maxTokens.toString(),
                    fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { sheetType = "temperature" }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("温度", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    String.format("%.2f", AgentStore.temperature),
                    fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            /* ================= 人格 ================= */
            Spacer(Modifier.height(20.dp))
            Label("人格")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenPersonas() }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("现在用的", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        AgentStore.persona.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text("管理 ›", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.primary)
            }

            msg?.let {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp),
                ) {
                    Text(it, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    /* ================= 从下面滑出来的那几个 ================= */
    when (sheetOf(sheetType)) {
        Sheet.Vendor -> BottomSheet(title = "服务商", onDismiss = { sheetType = "" }) {
            PROVIDERS.forEach { p ->
                val on = p.id == AgentStore.providerId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else Color.Transparent
                        )
                        .clickable { AgentStore.applyProvider(p); sheetType = "" }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Text(
                        (if (on) "● " else "○ ") + p.label,
                        fontSize = 13.5.sp,
                        color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    )
                    if (p.baseUrl.isNotBlank()) {
                        Text(
                            p.baseUrl,
                            fontSize = 10.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Sheet.Model -> BottomSheet(title = "模型", onDismiss = { sheetType = "" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("先问一下这家有哪些", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable(enabled = !busy) {
                            busy = true
                            msg = "正在拉模型列表…"
                            scope.launch {
                                AgentApi.listModels()
                                    .onSuccess { models = it; msg = "拿到 " + it.size + " 个" }
                                    .onFailure { msg = "拿不到：" + (it.message ?: "") }
                                busy = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(if (busy) "拉取中…" else "拉取模型列表", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(8.dp))

            models.forEach { m ->
                val on = m == AgentStore.model
                Text(
                    (if (on) "● " else "○ ") + m,
                    fontSize = 12.5.sp,
                    color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { AgentStore.updateModel(m); sheetType = "" }
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            // 最下面是"添加其他模型名"
            AddModelRow { AgentStore.updateModel(it); sheetType = "" }
        }

        Sheet.MaxToken -> BottomSheet(title = "最大回复长度", onDismiss = { sheetType = "" }) {
            listOf(
                1_000_000 to "不限（默认）",
                128_000 to "128k",
                32_768 to "32k",
                8_192 to "8k",
                4_096 to "4k",
            ).forEach { (v, label) ->
                val on = AgentStore.maxTokens == v
                Text(
                    (if (on) "● " else "○ ") + label,
                    fontSize = 13.sp,
                    color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { AgentStore.updateMaxTokens(v); sheetType = "" }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                )
            }
        }

        Sheet.Temperature -> BottomSheet(title = "温度", onDismiss = { sheetType = "" }) {
            listOf(0f, 0.3f, 0.7f, 1f, 1.3f, 1.8f, 2f).forEach { v ->
                val on = kotlin.math.abs(AgentStore.temperature - v) < 0.01f
                Text(
                    (if (on) "● " else "○ ") + String.format("%.2f", v),
                    fontSize = 13.sp,
                    color = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { AgentStore.updateTemperature(v); sheetType = "" }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                )
            }
        }

        null -> Unit
    }
}

private enum class Sheet { Vendor, Model, MaxToken, Temperature }

/** 字符串 → 枚举 */
private fun sheetOf(name: String): Sheet? = when (name) {
    "vendor" -> Sheet.Vendor
    "model" -> Sheet.Model
    "maxtoken" -> Sheet.MaxToken
    "temperature" -> Sheet.Temperature
    else -> null
}

/**
 * API Key 的输入栏：右边圆角输入框，带一个眼睛按钮。
 * 藏起来的时候只露**前 5 位和后 4 位**，中间一律 `*`。
 */
@Composable
private fun ApiKeyField(value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (visible) {
                // 看的见的时候直接编辑
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                )
                if (value.isBlank()) {
                    Text("sk-…", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else {
                // 看不见的时候只给掩码，点眼睛才进去编辑
                val masked = maskKey(value)
                Text(
                    masked.ifBlank { "sk-…" },
                    fontSize = 12.5.sp,
                    color = if (value.isBlank()) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable { visible = !visible },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (visible) "隐藏" else "显示", fontSize = 10.5.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

/** 前 5 位 + 后 4 位，中间打星 */
private fun maskKey(k: String): String {
    if (k.isBlank()) return ""
    if (k.length <= 9) return "*".repeat(k.length)
    return k.take(5) + "*".repeat((k.length - 9).coerceAtMost(24)) + k.takeLast(4)
}

/** 最下面那行：添加其他模型名 */
@Composable
private fun AddModelRow(onAdd: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable { editing = true }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (!editing) {
            Text("＋ 添加其他模型名", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                )
                if (text.isBlank()) {
                    Text("比如 deepseek-chat", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(
                    "加",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { if (text.isNotBlank()) onAdd(text.trim()) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(
        t,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Input(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        )
        if (value.isEmpty()) {
            Text(hint, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
