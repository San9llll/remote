package lo.naui.ui.agent

import android.app.AlertDialog
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lo.naui.agent.AgentStore
import lo.naui.ui.common.PageHeader
import lo.naui.ui.common.SectionTitle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference

/**
 * Agent 的配置。跟主题分开存，改完当页生效。
 *
 * 只要是对 OpenAI 兼容的接口（DeepSeek / OpenAI / 中转站都算），
 * 填对 Base URL + Key + 模型名就能跑。
 */
@Composable
fun AgentConfigScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    AgentStore.init(ctx)

    var tempDraft by remember { mutableStateOf(AgentStore.temperature) }

    fun edit(title: String, value: String, hint: String, onSave: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(value)
            setSelection(value.length)
            if (hint.isNotEmpty()) this.hint = hint
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        PageHeader(title = "Agent 配置", subtitle = "OpenAI 兼容接口", onBack = onBack)

        SectionTitle("接口")
        ConfigCard {
            ArrowPreference(
                title = "Base URL",
                summary = AgentStore.baseUrl,
                onClick = {
                    edit("Base URL", AgentStore.baseUrl, "https://api.deepseek.com/v1") {
                        AgentStore.updateBaseUrl(it)
                    }
                },
            )
            ArrowPreference(
                title = "API Key",
                summary = if (AgentStore.apiKey.isBlank()) "还没填" else "已填（" + AgentStore.apiKey.take(6) + "…）",
                onClick = {
                    edit("API Key", AgentStore.apiKey, "sk-…") { AgentStore.updateApiKey(it) }
                },
            )
            ArrowPreference(
                title = "模型",
                summary = AgentStore.model,
                onClick = {
                    edit("模型", AgentStore.model, "deepseek-chat") { AgentStore.updateModel(it) }
                },
            )
        }

        SectionTitle("生成参数")
        ConfigCard {
            SliderPreference(
                value = tempDraft,
                onValueChange = {
                    tempDraft = it
                    AgentStore.updateTemperature(it)
                },
                title = "温度",
                summary = "越小越稳，越大越发散",
                valueText = String.format("%.2f", tempDraft),
                valueRange = 0f..2f,
                steps = 19,
            )
            ArrowPreference(
                title = "最大回复长度",
                summary = AgentStore.maxTokens.toString() + " tokens",
                onClick = {
                    edit("最大回复长度", AgentStore.maxTokens.toString(), "2048") { v ->
                        v.trim().toIntOrNull()?.let { AgentStore.updateMaxTokens(it) }
                    }
                },
            )
        }

        SectionTitle("人格")
        ConfigCard {
            ArrowPreference(
                title = "系统提示词",
                summary = if (AgentStore.systemPrompt.isBlank()) "没写（直接用模型默认）"
                else AgentStore.systemPrompt.take(40) + if (AgentStore.systemPrompt.length > 40) "…" else "",
                onClick = {
                    edit("系统提示词", AgentStore.systemPrompt, "你是一个…") {
                        AgentStore.updateSystemPrompt(it)
                    }
                },
            )
        }

        SectionTitle("说明")
        ConfigCard {
            ArrowPreference(
                title = "怎么接别的模型",
                summary = "Base URL 填到 /v1 为止，路径里的 /chat/completions 会自己接上",
            )
            ArrowPreference(
                title = "图片",
                summary = "图片走 base64 塞进消息里，所以要选支持视觉的模型（如 deepseek-vl 之类）",
            )
            ArrowPreference(
                title = "文件",
                summary = "文本文件（256KB 以内）会读成文字拼在问题前面",
            )
        }
    }
}

@Composable
private fun ConfigCard(content: @Composable () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column { content() }
    }
}
