package lo.naui.ui.setup

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 第一次进入的准备界面。
 *
 * 参考 OShin 那种"分几波把该做的做完"的做法 —— 不是一上来就丢一个空界面，
 * 而是**一步一步带你配**：
 *
 * ```
 * ① 欢迎
 * ② 权限
 * ③ Agent 配置     ← 服务商 / Key / 模型，填完能测
 * ④ 基础设置        ← 主题、背景、工作区
 * ⑤ 完事
 * ```
 *
 * 中途退出会记住走到第几步（[SetupStore.lastStep]），下次接着来。
 * 走完置为 done，以后不再出现 —— 想再看就用**设置里的开发者工具**重置。
 */
@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    SetupStore.init(ctx)

    var step by remember { mutableStateOf(SetupStore.lastStep.coerceIn(0, SetupStore.TOTAL_STEPS - 1)) }

    // Agent 配置（第 3 步用）
    val agent = lo.naui.agent.AgentStore
    agent.init(ctx)
    var apiKey by remember { mutableStateOf(agent.apiKey) }
    // ⚠️ 实际字段叫 providerId（不是 provider）
    var provider by remember { mutableStateOf(agent.providerId) }
    var model by remember { mutableStateOf(agent.model) }

    val prefs = lo.naui.ui.theme.Prefs.current

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 56.dp, bottom = 32.dp),
        ) {

            // ---- 进度点 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(SetupStore.TOTAL_STEPS) { i ->
                    Box(
                        Modifier
                            .size(if (i == step) 22.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i <= step) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                            ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    (step + 1).toString() + " / " + SetupStore.TOTAL_STEPS,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.height(36.dp))

            when (step) {

                // ================= ① 欢迎 =================
                0 -> {
                    Text("Nakour", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "一个跑在手机上的本地工具箱。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(28.dp))
                    BulletLine("文件管理", "双栏、root / Shizuku、压缩解压")
                    BulletLine("终端", "真 PTY + xterm，能装 Termux 环境")
                    BulletLine("Agent", "能真动手操作手机的 AI")
                    BulletLine("书柜", "让 AI 写小说，还能分角色配音朗读")
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "下面几步把必要的东西配一下，两三分钟。\n" +
                            "每步都能跳过，之后在设置里也能改。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                // ================= ② 权限 =================
                1 -> {
                    Text("权限", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这些不是全都要 —— 用到对应功能时会再问你要一次。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(20.dp))
                    BulletLine("通知", "后台跑 Agent 时让你看到进度")
                    BulletLine("存储", "读写你自己的文件")
                    BulletLine("悬浮窗", "把任务进度飘在别的应用上面（可选）")
                    BulletLine("电池白名单", "别让系统把后台任务杀掉（可选）")
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "root / Shizuku 属于进阶玩法，不配也能用绝大部分功能。",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                // ================= ③ Agent 配置 =================
                2 -> {
                    Text("配 Agent", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "填一个 API 就能用了。这一步可以先跳过，之后再配。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(20.dp))

                    FieldLabel("服务商")
                    Spacer(Modifier.height(6.dp))
                    // 用实际那份预设表（`PROVIDERS`，一共 8 家）。
                    // 两行四列铺开 —— 一行八个太挤了。
                    lo.naui.agent.PROVIDERS.chunked(4).forEach { rowProviders ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowProviders.forEach { prov ->
                                val on = provider == prov.id
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (on) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .clickable {
                                            provider = prov.id
                                            // 选预设就把它带的那个模型填上
                                            if (prov.model.isNotBlank()) model = prov.model
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        prov.label.substringBefore("（").substringBefore(" "),
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        color = if (on) MiuixTheme.colorScheme.onPrimary
                                        else MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            // 补空位，不然最后一行会拉宽
                            repeat(4 - rowProviders.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                    FieldLabel("API Key")
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 12.5.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        )
                        if (apiKey.isEmpty()) {
                            Text(
                                "sk-…",
                                fontSize = 12.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    FieldLabel("模型")
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = model,
                            onValueChange = { model = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 12.5.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        )
                    }
                }

                // ================= ④ 基础设置 =================
                3 -> {
                    Text("顺手设一下", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "都能跳过，之后在设置里改。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(20.dp))

                    FieldLabel("明暗")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // ⚠️ updateDarkMode 收的是 DarkMode 枚举，不是下标
                        lo.naui.ui.theme.DarkMode.entries.forEach { mode ->
                            val on = prefs?.darkMode == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (on) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable { prefs?.updateDarkMode(mode) }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    mode.label,
                                    fontSize = 12.sp,
                                    color = if (on) MiuixTheme.colorScheme.onPrimary
                                    else MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    FieldLabel("背景样式")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        lo.naui.ui.theme.BgStyle.entries.forEach { st ->
                            val on = prefs?.bgStyle == st
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (on) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable { prefs?.updateBgStyle(st) }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    st.label,
                                    fontSize = 12.sp,
                                    color = if (on) MiuixTheme.colorScheme.onPrimary
                                    else MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    FieldLabel("Agent 工作区")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (agent.workspace.isBlank()) "还没设 —— 到设置里可以选一个文件夹，所有会话共用"
                        else agent.workspace,
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                // ================= ⑤ 完事 =================
                4 -> {
                    Text("行了", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "配置都存在本机，不上传任何服务器。\n" +
                            "（除了你自己填的那个 AI 服务商）",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "随时能在设置里改。想重新走一遍这个流程，" +
                            "去「设置 → 开发者工具 → 重新走一遍准备界面」。",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // ---- 底部按钮 ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                step--
                                SetupStore.saveStep(step)
                            }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("上一步", fontSize = 14.sp) }
                }

                Box(
                    Modifier
                        .weight(1.4f)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable {
                            // 第 3 步离开时把 Agent 配置存下来。
                            // 用 applyProvider(对象) —— 它会顺手把 baseUrl 也带上
                            if (step == 2) {
                                lo.naui.agent.PROVIDERS
                                    .firstOrNull { it.id == provider }
                                    ?.let { agent.applyProvider(it) }
                                agent.updateApiKey(apiKey)
                                agent.updateModel(model)
                            }
                            if (step >= SetupStore.TOTAL_STEPS - 1) {
                                SetupStore.finish()
                                onFinish()
                            } else {
                                step++
                                SetupStore.saveStep(step)
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (step >= SetupStore.TOTAL_STEPS - 1) "开始用" else "下一步",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 跳过（最后一页就不给了）
            if (step < SetupStore.TOTAL_STEPS - 1) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            SetupStore.finish()
                            onFinish()
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "跳过，我自己摸索",
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(s: String) {
    Text(s, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun BulletLine(title: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
