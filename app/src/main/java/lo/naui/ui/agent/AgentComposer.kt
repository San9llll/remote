package lo.naui.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 *输入栏最多长多高 —— **1.5行**。
 *
 *用户的要求：「输入栏优化输入的字不要全部显示出来，保留1.5行即可」。
 *以前输入框跟着内容长，写长了半屏都是字；现在框住高度，多出来的自己滚。
 *1行 =行高19sp，1.5行再加上下各8dp内边距 ≈46dp。
 */
private val COMPOSER_MAX =46.dp

/**
 * Agent页底下那条。
 *
 *用户要的样子：
 * · **液态玻璃**的一整条（不再是一堆灰色方块拼起来）
 * ·原来「📎文件」和「⚙设置」两个圆钮**合成一个 `+`**，点一下从上面展开这两个选择
 * ·输入框只露1.5行
 */
@Composable
fun AgentComposer(
 draft: String,
 onDraft: (String) -> Unit,
 sending: Boolean,
 ready: Boolean,
 onPickFile: () -> Unit,
 onOpenSettings: () -> Unit,
 onSend: () -> Unit,
) {
 var expanded by remember { mutableStateOf(false) }

 Column(
 Modifier
 .fillMaxWidth()
 .imePadding()
 .padding(horizontal =12.dp, vertical =10.dp),
 ) {
 // ----从 `+`展开出来的两个选择 ----
 AnimatedVisibility(
 visible = expanded,
 enter = expandVertically(tween(200)) + fadeIn(tween(150)),
 exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
 ) {
 Row(
 Modifier
 .fillMaxWidth()
 .padding(bottom =8.dp),
 horizontalArrangement = Arrangement.spacedBy(8.dp),
 ) {
 ChipItem("上传文件", "图片 /文本") {
 expanded = false
 onPickFile()
 }
 ChipItem("设置", "模型 ·参数 ·工具") {
 expanded = false
 onOpenSettings()
 }
 }
 }

 // ----玻璃的一条：+ ·输入 ·发送 ----
 AgentGlassCard(
 modifier = Modifier.fillMaxWidth(),
 shape = RoundedCornerShape(26.dp),
 contentPadding =6.dp,
 ) {
 Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
 PlusButton(expanded) { expanded = !expanded }

 Box(
 Modifier
 .weight(1f)
 .heightIn(min =34.dp, max = COMPOSER_MAX)
 .padding(horizontal =6.dp, vertical =8.dp),
 ) {
 if (draft.isEmpty()) {
 Text(
 "说点什么…",
 fontSize =13.5.sp,
 color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
 )
 }
 BasicTextField(
 value = draft,
 onValueChange = onDraft,
 modifier = Modifier
 .fillMaxWidth()
 .heightIn(max = COMPOSER_MAX),
 textStyle = TextStyle(
 fontSize =13.5.sp,
 lineHeight =19.sp,
 color = MiuixTheme.colorScheme.onSurface,
 ),
 cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
 )
 }

 SendButton(
			sending = sending,
			ready = ready,
			hasDraft = draft.isNotBlank(),
			onSend = onSend,
		)
 }
 }
 }
}

/**展开出来的那个小选项：玻璃卡里两行字 */
@Composable
private fun RowScope.ChipItem(title: String, sub: String, onClick: () -> Unit) {
 AgentGlassCard(
 modifier = Modifier
 .weight(1f)
 .clickable { onClick() },
 shape = RoundedCornerShape(16.dp),
 contentPadding =10.dp,
 ) {
 Column {
 Text(title, fontSize =13.sp, fontWeight = FontWeight.Medium)
 Spacer(Modifier.height(2.dp))
 Text(
 sub,
 fontSize =10.5.sp,
 color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
 maxLines =1,
 overflow = TextOverflow.Ellipsis,
 )
 }
 }
}

/**
 *那个 `+`。
 *
 *自己画的（两条线），展开时转45度变成 `×` ——跟卡片里那个箭头一个路子，
 *不用字符拼，免得字重和位置对不上。
 */
@Composable
private fun PlusButton(expanded: Boolean, onClick: () -> Unit) {
 val rot by animateFloatAsState(
 targetValue = if (expanded) 45f else 0f,
 animationSpec = tween(200),
 label = "plus_rot",
 )
 val tint = MiuixTheme.colorScheme.primary

 Box(
 Modifier
 .size(38.dp)
 .clip(CircleShape)
 .background(tint.copy(alpha =0.16f))
 .clickable { onClick() },
 contentAlignment = Alignment.Center,
 ) {
 Canvas(Modifier.size(15.dp).rotate(rot)) {
 val w = size.width
 val h = size.height
 val sw = w *0.12f
 drawLine(
 color = tint,
 start = Offset(w *0.5f, h *0.14f),
 end = Offset(w *0.5f, h *0.86f),
 strokeWidth = sw,
 cap = StrokeCap.Round,
 )
 drawLine(
 color = tint,
 start = Offset(w *0.14f, h *0.5f),
 end = Offset(w *0.86f, h *0.5f),
 strokeWidth = sw,
 cap = StrokeCap.Round,
 )
 }
 }
}

/**发送键 ——三种样子，跟着任务状态走。
 *
 *用户的要求：
 * ·没在跑 →向上的箭头
 * ·在跑、输入框空着 →一个圈在转（这时候它是个"状态"，不是按钮）
 * ·在跑、输入框里有字 →又变回箭头，按下去是**插话**：
 *不打断正在跑的那一轮，把话排进队列，等这一轮工具跑完接上
 *
 *圈和箭头都是自己画的（跟 `+`一个路子），不用字符拼 ——免得字重和位置对不上。
 */
@Composable
private fun SendButton(
	sending: Boolean,
	ready: Boolean,
	hasDraft: Boolean,
	onSend: () -> Unit,
) {
	val spin = sending && !hasDraft
	val active = ready && hasDraft
	val tint = MiuixTheme.colorScheme.primary
	val bg = when {
		active -> tint
		spin -> tint.copy(alpha =0.16f)
		else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha =0.18f)
	}
	val fg = when {
		active -> MiuixTheme.colorScheme.onPrimary
		spin -> tint
		else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
	}
	val turn by rememberInfiniteTransition().animateFloat(
		initialValue =0f,
		targetValue =360f,
		animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
	)

	Box(
		Modifier
			.size(38.dp)
			.clip(CircleShape)
			.background(bg)
			.clickable(enabled = active) { onSend() },
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.size(17.dp).rotate(if (spin) turn else 0f)) {
			val w = size.width
			val h = size.height
			val sw = w *0.13f
			if (spin) {
				//一个缺口的圈 ——转起来就是在忙
				drawArc(
					color = fg,
					startAngle =0f,
					sweepAngle =280f,
					useCenter = false,
					style = Stroke(width = sw, cap = StrokeCap.Round),
					topLeft = Offset(w *0.07f, h *0.07f),
					size = androidx.compose.ui.geometry.Size(w *0.86f, h *0.86f),
				)
			} else {
				//向上的箭头：一竖 +两撇
				drawLine(fg, Offset(w *0.5f, h *0.84f), Offset(w *0.5f, h *0.18f), sw, StrokeCap.Round)
				drawLine(fg, Offset(w *0.5f, h *0.18f), Offset(w *0.22f, h *0.46f), sw, StrokeCap.Round)
				drawLine(fg, Offset(w *0.5f, h *0.18f), Offset(w *0.78f, h *0.46f), sw, StrokeCap.Round)
			}
		}
	}
}
