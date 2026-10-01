// 玻璃卡片 —— 两套画法，主题里切
//
// 仿玻璃（默认）：纯 2D 叠层，零 GPU 折射。
//   参考项目当年把 GPU 折射铺到多张卡上，Android 16 启动即崩，
//   退回这套之后一直稳，所以它是默认。
//
// 液态玻璃（可选）：com.kyant.backdrop 的真折射。
//   结构必须守官方那个口诀：**layerBackdrop 标背景，drawBackdrop 画玻璃，两者不可互相套**。
//   外壳里只有「背景素材层」挂 layerBackdrop，卡片一律在外面用 drawBackdrop 采样它。
//
// 所有旋钮（模糊 / 折射 / 透明度 / 圆角）都在 主题 →「卡片」里调，
// 以后新加的卡片只要用这个组件，就自动跟着一起变。
package lo.naui.ui.component

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import lo.naui.ui.theme.CardStyle
import lo.naui.ui.theme.LocalThemeModeState
import lo.naui.ui.theme.Prefs
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外壳那层 layerBackdrop。
 *
 * 挂在 CompositionLocal 上，这样任何一个 GlassCard 不传 backdrop 也能拿到 ——
 * 不然每加一个页面都得记着往下传一遍，很容易漏（设置页、Agent 页之前就是这么漏掉的，
 * 结果只有仿玻璃没有真折射）。
 */
val LocalGlassBackdrop = androidx.compose.runtime.compositionLocalOf<Backdrop?> { null }

/** 卡片形状：不传就按主题里的圆角参数来 */
@Composable
fun glassShape(): Shape = RoundedCornerShape((Prefs.current?.glassRadius ?: 20).dp)

/**
 * 玻璃卡。
 *
 * [backdrop] 传外壳那层 layerBackdrop（没有的话液态玻璃会自动退回仿玻璃）。
 * [shape] 不传就吃主题里的圆角。
 * [contentPadding] 给了就自己套内边距。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentPadding: Dp? = null,
    /**
     * 入场动画。
     *
     * 传 **0、1、2…** 就会从**侧边栏那一侧**滑进来，并且按序号错开出现，
     * 落位时用 `spring` 回弹一下（果冻感）。不传（-1）就老老实实直接显示。
     *
     * 传个序号就行，不用每页自己写动画 —— 全项目一次生效。
     */
    enterIndex: Int = -1,
    content: @Composable BoxScope.() -> Unit,
) {
    // ---- 入场：从左边（侧栏那侧）滑进来 + 错开 + 果冻回弹 ----
    var entered by remember { mutableStateOf(enterIndex < 0) }
    LaunchedEffect(enterIndex) {
        if (enterIndex >= 0) {
            kotlinx.coroutines.delay(enterIndex * 55L)   // 按序号错开
            entered = true
        }
    }
    val enterShift by animateFloatAsState(
        targetValue = if (entered) 0f else 1f,
        animationSpec = spring(
            // 阻尼调小一点，落位时会往回弹一下 = 果冻
            dampingRatio = 0.42f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "card_enter",
    )
    val enterFade by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(260),
        label = "card_fade",
    )

    val isDark = LocalThemeModeState.current.isDark
    val surface = MiuixTheme.colorScheme.surface
    val tint = MiuixTheme.colorScheme.primary

    val prefs = Prefs.current
    val radius = (prefs?.glassRadius ?: 20).dp
    val realShape: Shape = shape ?: RoundedCornerShape(radius)
    val blurDp = prefs?.glassBlur ?: 20f
    val lensDp = prefs?.glassLens ?: 12f
    // 深色下玻璃要更"实"，不然字看不清
    val alpha = (prefs?.glassAlpha ?: 0.58f) + if (isDark) 0.12f else 0f
    val fill = surface.copy(alpha = alpha.coerceIn(0.08f, 0.96f))
    val edge = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) 0.22f else 0.62f),
            Color.White.copy(alpha = if (isDark) 0.06f else 0.22f),
            Color.White.copy(alpha = if (isDark) 0.16f else 0.40f),
        )
    )

    // 显式给的优先，没给就找外壳要
    val bd = backdrop ?: LocalGlassBackdrop.current
    val liquid = Prefs.current?.cardStyle == CardStyle.Liquid

    if (liquid && bd != null) {
        /* ---------------- 液态玻璃（真折射） ---------------- */
        Box(
            modifier = modifier
                // 入场：从侧栏那一侧滑进来，落位回弹
                .graphicsLayer {
                    // ⚠️ 必须写 this. —— 这个文件里本来就有个 val alpha（玻璃的不透明度），
                    // 而 Kotlin 里**局部变量优先于接收者成员**，光写 alpha = 会去改那个 val
                    this.translationX = -enterShift * 64f.dp.toPx()
                    this.alpha = enterFade
                }
                .clip(realShape)
                .drawBackdrop(
                    backdrop = bd,
                    shape = { realShape },
                    effects = {
                        if (blurDp > 0f) blur(blurDp.dp.toPx())
                        if (lensDp > 0f) lens(lensDp.dp.toPx(), (lensDp * 1.33f).dp.toPx(), true)
                    },
                    onDrawSurface = {
                        // 一层半透明面板色，保证卡片上的字读得清
                        drawRect(fill)
                    },
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
        /* ---------------- 仿玻璃（默认，纯 2D） ---------------- */
        Box(
            modifier = modifier
                // 入场：从侧栏那一侧滑进来，落位回弹
                .graphicsLayer {
                    // ⚠️ 必须写 this. —— 这个文件里本来就有个 val alpha（玻璃的不透明度），
                    // 而 Kotlin 里**局部变量优先于接收者成员**，光写 alpha = 会去改那个 val
                    this.translationX = -enterShift * 64f.dp.toPx()
                    this.alpha = enterFade
                }
                .clip(realShape)
                .background(fill),
        ) {
            // 左上角主高光 + 底部反射光
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.12f else 0.38f),
                            Color.White.copy(alpha = 0.02f),
                            tint.copy(alpha = 0.05f),
                            Color.White.copy(alpha = if (isDark) 0.06f else 0.16f),
                        ),
                    )
                )
            )
            // 内发光边
            Box(
                Modifier.matchParentSize().border(1.dp, edge, realShape)
            )
            if (contentPadding != null) {
                Box(Modifier.padding(contentPadding)) { content() }
            } else {
                content()
            }
        }
    }
}
