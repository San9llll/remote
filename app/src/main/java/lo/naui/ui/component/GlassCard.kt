// 仿玻璃卡片 —— 纯 2D 绘制，零 GPU 折射
//
// 为什么默认不做真 backdrop：
//   参考项目在 Android 16 上把 GPU 折射铺到主页四五张卡上，启动即崩；
//   退回"仿玻璃"（纯 2D 叠层）之后一直稳。
//
//   所以这套是**默认方案**：一个着色器都不碰，
//   半透明底 → 左上角主高光 → 底部反射光 → 细描边 → 内发光边。
//
// 液态玻璃（可选）：
//   在主题里把「卡片风格」切成液态玻璃，才会走 com.kyant.backdrop 的真折射。
//   切过去之后**所有 GlassCard** 都会用真折射 —— 这正是崩过的那种用法，
//   所以它只是选项，默认不开。要试的话一次别开太多张卡。
package lo.naui.ui.component

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
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import lo.naui.ui.theme.CardStyle
import lo.naui.ui.theme.LocalThemeModeState
import lo.naui.ui.theme.Prefs
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃卡。
 *
 * 走哪套看主题里的「卡片风格」：
 *  - [CardStyle.Legacy]（默认）→ 纯 2D 叠层，[backdrop] 只当参数收着不用
 *  - [CardStyle.Liquid] → 真折射；但必须有 [backdrop]（外壳那层 layerBackdrop），
 *    没有的话自动退回 2D，免得画出个空的
 *
 * [contentPadding] 给了就自己套内边距。
 * [bgImage] 可选：卡片背景图，会在玻璃底和高光**之间**铺进来。
 * [bgAlpha] 背景图的透明度。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: Dp? = null,
    bgImage: @Composable (() -> Unit)? = null,
    bgAlpha: Float = 0.5f,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = LocalThemeModeState.current.isDark
    val surface = MiuixTheme.colorScheme.surface
    val tint = MiuixTheme.colorScheme.primary

    // 深色下玻璃要更"实"，不然字看不清
    val fill = if (isDark) surface.copy(alpha = 0.70f) else surface.copy(alpha = 0.58f)

    val bd = backdrop
    val wantLiquid = Prefs.current?.cardStyle == CardStyle.Liquid

    if (wantLiquid && bd != null) {
        /* ---------------- 液态玻璃（真折射） ---------------- */
        Box(
            modifier = modifier
                .clip(shape)
                .drawBackdrop(
                    backdrop = bd,
                    shape = { shape },
                    effects = {
                        blur(6f.dp.toPx())
                        vibrancy()
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow.Default },
                    onDrawSurface = { drawRect(fill) },
                ),
        ) {
            // 卡片自己的背景图（可选）
            if (bgImage != null) {
                Box(Modifier.matchParentSize()) { bgImage() }
                Box(
                    Modifier.matchParentSize().background(
                        surface.copy(alpha = (1f - bgAlpha).coerceIn(0f, 1f))
                    )
                )
            }

            // 内发光边留一条：真折射有高光，但边缘还得有个"玻璃厚度"的意思
            Box(
                Modifier.matchParentSize().border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.22f else 0.62f),
                            Color.White.copy(alpha = if (isDark) 0.06f else 0.22f),
                            Color.White.copy(alpha = if (isDark) 0.16f else 0.40f),
                        )
                    ),
                    shape = shape,
                )
            )

            if (contentPadding != null) {
                Box(Modifier.padding(contentPadding)) { content() }
            } else {
                content()
            }
        }
        return
    }

    /* ---------------- 仿玻璃（默认，纯 2D） ---------------- */
    Box(
        modifier = modifier
            .clip(shape)
            // ① 玻璃底
            .background(fill),
    ) {
        // ② 卡片自己的背景图（可选）—— 夹在底和高光之间，所以透明度可调且不破坏玻璃
        if (bgImage != null) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.matchParentSize()) { bgImage() }
                Box(
                    Modifier.matchParentSize().background(
                        surface.copy(alpha = (1f - bgAlpha).coerceIn(0f, 1f))
                    )
                )
            }
        }

        // ③ 左上角主高光 + 底部反射光（玻璃的通透感主要靠这两层）
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

        // ④ 内发光边：上边亮、下边暗，模拟玻璃边缘的折射
        Box(
            Modifier.matchParentSize().border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDark) 0.22f else 0.62f),
                        Color.White.copy(alpha = if (isDark) 0.06f else 0.22f),
                        Color.White.copy(alpha = if (isDark) 0.16f else 0.40f),
                    )
                ),
                shape = shape,
            )
        )

        // ⑤ 内容
        if (contentPadding != null) {
            Box(Modifier.padding(contentPadding)) { content() }
        } else {
            content()
        }
    }
}
