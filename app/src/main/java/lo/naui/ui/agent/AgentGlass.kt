package lo.naui.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import lo.naui.agent.AgentGlassPrefs
import lo.naui.ui.component.LocalGlassBackdrop
import lo.naui.ui.theme.LocalThemeModeState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent页专用的玻璃卡。
 *
 *跟 [lo.naui.ui.component.GlassCard]是两个东西：
 *·那个读的是**主题**里那几个旋钮（全 App的卡片共用一份）
 *·这个读的是 [AgentGlassPrefs]里那一份（**Agent页单独一套**）
 *
 *用户的原话：「agent页的玻璃参数与主题参数分开」。
 *所以参数存在 nakour_agent_glass那份里，改的入口在 **Agent配置页**，
 *跟主题页那几根滑块互不干扰 ——动主题不会把 Agent页的观感一起带跑。
 *
 *折射那层还是共用外壳挂的 [LocalGlassBackdrop]（那是 AppShell铺的背景素材层，
 *每页自己造一份的话卡片就采不到背景了）。
 */
@Composable
fun AgentGlassCard(
 modifier: Modifier = Modifier,
 shape: Shape? = null,
 contentPadding: Dp? = null,
 content: @Composable BoxScope.() -> Unit,
) {
 val isDark = LocalThemeModeState.current.isDark
 val realShape: Shape = shape ?: RoundedCornerShape(AgentGlassPrefs.radius.dp)

 val blurDp = AgentGlassPrefs.blur
 val lensDp = AgentGlassPrefs.lens
 //深色下玻璃要更实一点，不然卡片上的字看不清
 val alpha = (AgentGlassPrefs.alpha + if (isDark) 0.10f else 0f).coerceIn(0.05f,0.96f)
 val fill = MiuixTheme.colorScheme.surface.copy(alpha = alpha)

 //内发光边：上亮下暗，模拟玻璃那道折射出来的边
 val edge = Brush.verticalGradient(
 colors = listOf(
 Color.White.copy(alpha = if (isDark) 0.20f else 0.58f),
 Color.White.copy(alpha = if (isDark) 0.05f else 0.20f),
 Color.White.copy(alpha = if (isDark) 0.14f else 0.36f),
 )
 )

 val bd = LocalGlassBackdrop.current

 if (AgentGlassPrefs.liquid && bd != null) {
 /* ----------------液态玻璃：真折射 ---------------- */
 Box(
 modifier = modifier
 .clip(realShape)
 .drawBackdrop(
 backdrop = bd,
 shape = { realShape },
 effects = {
 if (blurDp >0f) blur(blurDp.dp.toPx())
 if (lensDp >0f) lens(lensDp.dp.toPx(), (lensDp *1.33f).dp.toPx(), true)
 },
 onDrawSurface = { drawRect(fill) },
 ),
 ) {
 Box(Modifier.matchParentSize().border(1.dp, edge, realShape))
 if (contentPadding != null) {
 Box(Modifier.padding(contentPadding)) { content() }
 } else {
 content()
 }
 }
 } else {
 /* ----------------仿玻璃：纯2D叠层（退路） ---------------- */
 Box(
 modifier = modifier
 .clip(realShape)
 .background(fill),
 ) {
 Box(Modifier.matchParentSize().border(1.dp, edge, realShape))
 if (contentPadding != null) {
 Box(Modifier.padding(contentPadding)) { content() }
 } else {
 content()
 }
 }
 }
}
