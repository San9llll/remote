// 仿玻璃卡片 —— 纯 2D 绘制，零 GPU 折射
//
// 为什么不做真 backdrop：
//   v4.4 / v4.6 在 Android 16 上启动即崩，v5.5（卡片改仿玻璃）能正常开。
//   已知 Aster 原版只在**底栏一处**用 drawBackdrop，而我把 GPU 折射铺到了
//   主页四五张卡上 —— 那不是它的用法。多张卡同时开 RenderEffect，机器扛不住。
//
// 所以这里用叠层把"玻璃感"堆出来，一个着色器都不碰：
//   半透明底 → 左上角主高光 → 底部反射光 → 细描边 → 内发光边
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
import lo.naui.ui.theme.LocalThemeModeState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃卡（仿玻璃加强版）。
 *
 * [backdrop] 参数为兼容保留，不参与绘制。
 * [contentPadding] 给了就自己套内边距。
 * [bgImage] 可选：卡片背景图（主题里给某张卡选的图），会在玻璃底和高光**之间**铺进来，
 *           所以调透明度时玻璃感还在。
 * [bgAlpha] 背景图的透明度。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    // 圆角可由热包 layout.cardRadius 调整（默认 20）
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

    Box(
        modifier = modifier
            .clip(shape)
            // ① 玻璃底
            .background(fill),
    ) {
        // ② 卡片自己的背景图（可选）—— 夹在底和高光之间，所以透明度可调且不破坏玻璃
        if (bgImage != null) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0f))) {
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
