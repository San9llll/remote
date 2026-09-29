// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lo.naui.ui.theme.LocalThemeModeState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全景背景。
 *
 * 现在**画两遍**，因为玻璃卡要的是「清晰的原图」：
 *
 *  ① 素材层（blurred = false, drawDecor = false）
 *     挂在 layerBackdrop 里，是玻璃卡 drawBackdrop 采样的对象。
 *     必须是清晰、不压黑的 —— 之前它自己先糊一遍再压黑纱，
 *     玻璃卡折射出来就是一团黑，看上去就像"没读到我的图"。
 *     这一层会被 ② 完全盖住，肉眼看不见。
 *
 *  ② 视觉层（blurred = true, drawDecor = true）
 *     糊 32dp + 压黑纱，这才是你眼睛看到的那张背景。
 */
@Composable
fun HomeSceneBackdrop(
    wallpaper: ImageBitmap?,
    railWidth: Dp,
    blurred: Boolean = true,
    drawDecor: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeModeState.current.isDark
    val scrim = if (isDark) 0.42f else 0.08f
    val railAlphaTop = if (isDark) 0.60f else 0.22f
    val railAlphaMid = if (isDark) 0.44f else 0.14f

    Box(modifier.fillMaxSize().clipToBounds()) {
        // 兜底：没有壁纸时给一条主题渐变，不至于是一片白
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MiuixTheme.colorScheme.primaryContainer,
                            MiuixTheme.colorScheme.secondaryContainer,
                            MiuixTheme.colorScheme.surface,
                        )
                    )
                )
        )

        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (blurred) {
                            Modifier.blur(32.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                        } else {
                            Modifier
                        }
                    ),
            )
        }

        if (drawDecor) {
            // 底纱（浅色几乎透明，颜色留住；深色才压重）
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))

            // 导轨那条：用主题面板色轻轻带过，白字在上面读得清，壁纸颜色还在
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(railWidth + 20.dp)
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to MiuixTheme.colorScheme.surface.copy(alpha = railAlphaTop),
                                0.72f to MiuixTheme.colorScheme.surface.copy(alpha = railAlphaMid),
                                1f to Color.Transparent,
                            ),
                        )
                    )
            )
        }
    }
}
