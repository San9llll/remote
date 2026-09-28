package lo.naui.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 排版对齐 Aster 的 SettingsComponents：
 * 分组标题用 Miuix 的 SmallTitle，卡片自己带 12dp 左右内边距和 8dp 下边距。
 */
@Composable
fun SectionTitle(t: String) {
    SmallTitle(text = t)
}

/** 一张分组卡（Aster 的 SettingsCard） */
@Composable
fun GroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        content()
    }
}

/** 标题 + 卡（Aster 的 SettingsSectionCard） */
@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title)
        GroupCard(content = content)
    }
}

/** 子页面顶栏：返回 + 标题 + 右侧动作（自动避开状态栏，否则返回键被挡住点不到） */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 关键：把状态栏的高度让出来，不然返回键正好落在状态栏底下
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).clickable { onBack() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("← 返回", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (action != null && onAction != null) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(action, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

/** 只读一行：标题 + 值 */
@Composable
fun InfoRow(title: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value ?: "—", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 两行一行：标题 + 副标题，右侧一个值 */
@Composable
fun ClickRow(title: String, subtitle: String? = null, value: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (value != null) {
            Text(value, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

/** 动作行：标题 + 副标题 + 右侧胶囊按钮 */
@Composable
fun ActionRow(title: String, subtitle: String? = null, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .background(
                    if (enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.10f)
                )
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                action, fontSize = 13.sp,
                color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 计数小块：label 15sp + 值 26sp（对齐 Aster 的计数卡） */
@Composable
fun CountTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                if (unit != null) Text(unit, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
