package lo.naui.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import lo.naui.agent.CardEvent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ----------------卡片正文 ---------------- */

/**角标旋转的两个角度 */
private const val BADGE_ON =90f
private const val BADGE_OFF =0f

/**目录那一段最多占多宽 */
private val DIR_MAX =130.dp

/**思考那一行最多露多少字（点开弹窗看全文） */
private const val THINK_PREVIEW =40

/**
 *一条回复里的一块。
 *
 *用户的要求：「不要将思考 /正文 /执行命令分成三大板块，
 *这样就不知道命令执行时 Agent在说啥了」。
 *
 *所以不再按"类型"归类，而是**按发生的先后顺序**排：
 *想 →说 →做 →再想 →再说 →再做 ——跟它干活的节奏一样。
 *只有**挨在一起的同类**才并成一块（连着三个命令并成一组，
 *中间的思考不会把它们撕成三份）。
 */
private sealed interface Blk {
 data class Think(val text: String) : Blk
 data class Body(val text: String) : Blk
 data class Tools(val list: List<CardEvent>) : Blk
}

/**把事件流压成块序列：相邻同类合并，不同类型保持原顺序 */
private fun group(events: List<CardEvent>): List<Blk> {
 val out = mutableListOf<Blk>()
 var i =0
 while (i < events.size) {
 when (events[i].kind) {
 CardEvent.THINK -> {
 val sb = StringBuilder()
 while (i < events.size && events[i].kind == CardEvent.THINK) {
 sb.append(events[i].text)
 i++
 }
 out += Blk.Think(sb.toString())
 }
 CardEvent.TEXT -> {
 val sb = StringBuilder()
 while (i < events.size && events[i].kind == CardEvent.TEXT) {
 sb.append(events[i].text)
 i++
 }
 out += Blk.Body(sb.toString())
 }
 else -> {
 val list = mutableListOf<CardEvent>()
 while (i < events.size && events[i].kind == CardEvent.TOOL) {
 list += events[i]
 i++
 }
 out += Blk.Tools(list)
 }
 }
 }
 return out
}

/**
 *一条回复的正文。
 *
 *顺序就是模型干活的顺序，一块挨着一块，流式的时候是"往下长"的。
 */
@Composable
internal fun CardBody(
 events: List<CardEvent>,
 fallbackText: String = "",
 onOpenUrl: (String) -> Unit = {},
 onOpenPath: (String) -> Unit = {},
 runningLabel: String = "",
 runningHint: String = "",
 runningDir: String = "",
) {
 //老消息（1.03之前存的）没有块，只有一整段文本
 if (events.isEmpty()) {
 if (runningLabel.isNotBlank()) RunningRow(runningLabel, runningHint, runningDir)
 if (fallbackText.isNotBlank()) {
 RichText(
 text = fallbackText,
 highlight = true,
 onOpenUrl = onOpenUrl,
 onOpenPath = onOpenPath,
 )
 }
 return
 }

 val blocks = remember(events) { group(events) }

 //点开哪一段思考（下标，-1表示没开）
 var thinkOpen by remember { mutableStateOf(-1) }

 Column(Modifier.fillMaxWidth()) {
 var first = true
 blocks.forEachIndexed { idx, b ->
 if (!first) Spacer(Modifier.height(7.dp))
 first = false
 when (b) {
 is Blk.Think -> ThinkLine(b.text) { thinkOpen = idx }
 is Blk.Body -> RichText(
 text = b.text,
 highlight = true,
 onOpenUrl = onOpenUrl,
 onOpenPath = onOpenPath,
 )
 is Blk.Tools -> ToolGroup(b.list)
 }
 }

 //老消息只有工具块、没有正文块，那正文得单独补一行出来
 if (fallbackText.isNotBlank() && blocks.none { it is Blk.Body }) {
 Spacer(Modifier.height(7.dp))
 RichText(
 text = fallbackText,
 highlight = true,
 onOpenUrl = onOpenUrl,
 onOpenPath = onOpenPath,
 )
 }

 if (runningLabel.isNotBlank()) {
 Spacer(Modifier.height(7.dp))
 RunningRow(runningLabel, runningHint, runningDir)
 }
 }

 val openText = (blocks.getOrNull(thinkOpen) as? Blk.Think)?.text
 if (openText != null) ThinkDialog(openText) { thinkOpen = -1 }
}

/**
 *思考那一行。
 *
 *格式：`思考`（正常字体）空格内容（更浅的字体），**只占一行**，
 *行尾接 `...`。内容多了掐尾巴 ——要看全文就点它。
 */
@Composable
private fun ThinkLine(text: String, onClick: () -> Unit) {
 val snippet = text.replace('\n', ' ').trim()
 if (snippet.isBlank()) return
 val short = if (snippet.length > THINK_PREVIEW) snippet.take(THINK_PREVIEW) else snippet
 Row(
 Modifier
 .fillMaxWidth()
 .clickable { onClick() },
 verticalAlignment = Alignment.CenterVertically,
 ) {
 Text(
 "思考",
 fontSize =12.5.sp,
 color = MiuixTheme.colorScheme.onSurface,
 )
 Spacer(Modifier.width(6.dp))
 Text(
 short + "...",
 fontSize =12.sp,
 color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha =0.72f),
 maxLines =1,
 overflow = TextOverflow.Ellipsis,
 modifier = Modifier.weight(1f),
 )
 }
}

/**点思考那一行弹出来的：完整思考过程 */
@Composable
private fun ThinkDialog(text: String, onDismiss: () -> Unit) {
 Dialog(
 onDismissRequest = onDismiss,
 properties = DialogProperties(usePlatformDefaultWidth = false),
 ) {
 Column(
 Modifier
 .fillMaxWidth(0.88f)
 .clip(RoundedCornerShape(18.dp))
 .background(MiuixTheme.colorScheme.surfaceContainerHigh)
 .padding(16.dp),
 ) {
 Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
 Text(
 "思考过程",
 fontSize =14.sp,
 color = MiuixTheme.colorScheme.onSurface,
 )
 Spacer(Modifier.weight(1f))
 Text(
 "收起",
 fontSize =12.sp,
 color = MiuixTheme.colorScheme.primary,
 modifier = Modifier.clickable { onDismiss() },
 )
 }
 Spacer(Modifier.height(10.dp))
 Column(
 Modifier
 .fillMaxWidth()
 .height(360.dp)
 .verticalScroll(rememberScrollState()),
 ) {
 Text(
 text,
 fontSize =12.sp,
 color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
 )
 }
 }
 }
}

/**
 *挨在一起的一批命令。
 *
 *组头那一行：一个手画的箭头（点了转90度）+「执行了 N条命令」+总耗时。
 *展开后：左边一条竖直淡线，右边按顺序把每条命令列出来。
 */
@Composable
private fun ToolGroup(tools: List<CardEvent>) {
 var open by remember { mutableStateOf(false) }
 val rot by animateFloatAsState(
 targetValue = if (open) BADGE_ON else BADGE_OFF,
 animationSpec = tween(180),
 label = "group_badge",
 )
 val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary
 val lineColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha =0.28f)
 val bad = tools.any { !it.ok }

 Column(Modifier.fillMaxWidth()) {
 Row(
 Modifier
 .fillMaxWidth()
 .clickable { open = !open },
 verticalAlignment = Alignment.CenterVertically,
 ) {
 Chevron(
 rot = rot,
 color = if (bad) MiuixTheme.colorScheme.error
 else MiuixTheme.colorScheme.primary,
 )
 Spacer(Modifier.width(7.dp))
 Text(
 "执行了 " + tools.size + "条命令",
 fontSize =11.5.sp,
 color = if (bad) MiuixTheme.colorScheme.error
 else MiuixTheme.colorScheme.primary,
 )
 Spacer(Modifier.weight(1f))
 val all = tools.sumOf { it.millis }
 if (all >0L) {
 Text(
 String.format(java.util.Locale.US, "%.1fs", all /1000.0),
 fontSize =10.sp,
 color = dim.copy(alpha =0.85f),
 )
 }
 }

 AnimatedVisibility(
 visible = open,
 enter = expandVertically(tween(200)) + fadeIn(tween(160)),
 exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
 ) {
 Column(
 Modifier
 .fillMaxWidth()
 .padding(top =4.dp)
 .drawBehind {
 //左边那条竖直淡线：整组共用一条
 val x =0.5.dp.toPx()
 drawLine(
 color = lineColor,
 start = Offset(x,0f),
 end = Offset(x, size.height),
 strokeWidth =1.dp.toPx(),
 )
 }
 .padding(start =9.dp),
 ) {
 tools.forEachIndexed { i, e ->
 if (i >0) Spacer(Modifier.height(7.dp))
 ToolRow(e)
 }
 }
 }
 }
}

/**
 *组里的一条命令。
 *
 *上面一行：人话名字 +耗时 +目录。
 *下面：具体要执行的东西（命令原文 /路径），等宽小字，最多三行。
 */
@Composable
private fun ToolRow(e: CardEvent) {
 val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary
 val body = when {
 e.detail.isNotBlank() -> e.detail
 e.brief.isNotBlank() -> e.brief
 else -> "（这次没有命令原文，是个内部动作）"
 }
 Column(Modifier.fillMaxWidth()) {
 Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
 Text(
 e.label,
 fontSize =10.5.sp,
 color = if (e.ok) dim else MiuixTheme.colorScheme.error,
 maxLines =1,
 overflow = TextOverflow.Ellipsis,
 )
 Spacer(Modifier.weight(1f))
 if (e.cost().isNotBlank()) {
 Text(e.cost(), fontSize =10.sp, color = dim.copy(alpha =0.85f))
 }
 if (e.dir.isNotBlank()) {
 Spacer(Modifier.width(8.dp))
 Text(
 e.dir,
 fontSize =10.sp,
 color = dim.copy(alpha =0.7f),
 maxLines =1,
 overflow = TextOverflow.Ellipsis,
 modifier = Modifier.widthIn(max = DIR_MAX),
 )
 }
 }
 BasicText(
 text = body,
 style = TextStyle(
 fontSize =9.5.sp,
 fontFamily = FontFamily.Monospace,
 color = MiuixTheme.colorScheme.onSurface.copy(alpha =0.82f),
 ),
 maxLines =3,
 overflow = TextOverflow.Ellipsis,
 modifier = Modifier
 .fillMaxWidth()
 .padding(top =3.dp)
 .clip(RoundedCornerShape(6.dp))
 .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha =0.55f))
 .padding(horizontal =7.dp, vertical =5.dp),
 )
 }
}

/**
 *手画的箭头。
 *
 *以前是拿字符 `▸`顶上去的 ——字重、位置、和圈的大小都对不上，
 *用户说"用其他方式绘制一个更好看的"。所以这里用 Path + Stroke画：
 *三条线段，圆角接头，粗细跟着尺寸缩放。
 */
@Composable
private fun Chevron(rot: Float, color: Color, size: Dp =14.dp) {
 Canvas(
 Modifier
 .size(size)
 .graphicsLayer { rotationZ = rot },
 ) {
 val w = this.size.width
 val h = this.size.height
 val path = Path()
 path.moveTo(w *0.36f, h *0.26f)
 path.lineTo(w *0.66f, h *0.5f)
 path.lineTo(w *0.36f, h *0.74f)
 drawPath(
 path = path,
 color = color,
 style = Stroke(
 width = w *0.14f,
 cap = StrokeCap.Round,
 join = StrokeJoin.Round,
 ),
 )
 }
}

/**正在跑的那一条：一个会呼吸的角标 +名字 +在干嘛 +目录 */
@Composable
private fun RunningRow(label: String, hint: String, dir: String) {
 val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary
 val transition = rememberInfiniteTransition(label = "rr")
 val glow by transition.animateFloat(
 initialValue =0.18f,
 targetValue =0.5f,
 animationSpec = infiniteRepeatable(
 animation = tween(720, easing = LinearEasing),
 repeatMode = RepeatMode.Reverse,
 ),
 label = "rr_glow",
 )

 Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
 Box(
 Modifier
 .size(15.dp)
 .clip(CircleShape)
 .background(MiuixTheme.colorScheme.primary.copy(alpha = glow)),
 contentAlignment = Alignment.Center,
 ) {
 Chevron(rot = BADGE_OFF, color = MiuixTheme.colorScheme.primary, size =10.dp)
 }
 Spacer(Modifier.width(6.dp))
 Text(label, fontSize =10.5.sp, color = MiuixTheme.colorScheme.primary)
 if (hint.isNotBlank()) {
 Spacer(Modifier.width(6.dp))
 Text(
 hint,
 fontSize =10.sp,
 color = dim,
 maxLines =1,
 overflow = TextOverflow.Ellipsis,
 )
 }
 Spacer(Modifier.weight(1f))
 if (dir.isNotBlank()) {
 Text(
 dir,
 fontSize =10.sp,
 color = dim.copy(alpha =0.7f),
 maxLines =1,
 overflow = TextOverflow.Ellipsis,
 modifier = Modifier.widthIn(max = DIR_MAX),
 )
 }
 }
}
