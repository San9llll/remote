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
 * ## 1.00.0 的大改：拆成"图"和"纱"两半
 *
 * 原来这个函数画两遍（`blurred = false` 的素材层 + `blurred = true` 的视觉层），
 * 为的是给玻璃卡一份**清晰、没压黑**的折射素材 —— 老注释里写着原因：
 * "之前它自己先糊一遍再压黑纱，玻璃卡折射出来就是一团黑"。
 * 代价是全屏壁纸每帧画两遍。
 *
 * 现在只画一遍，但把**压黑纱挪到采样层外面**（[HomeSceneDecor]）：
 *
 * ```
 * [layerBackdrop 里]  兜底渐变 → 壁纸（可调节模糊）
 * [layerBackdrop 外]  压黑纱 → 导轨那条渐变
 * [再上面]            内容层（卡片 drawBackdrop 采样上面那层）
 * ```
 *
 * 卡片采到的是**糊过但没压黑**的壁纸，所以既不黑、又跟着背景模糊滑块走；
 * 用户眼睛看到的是纱后面的版本，跟原来一样。全屏壁纸从两遍变一遍。
 *
 * @param bgBlur 背景模糊半径（主题页那个滑块），0 = 不糊
 */
@Composable
fun HomeSceneBackdrop(
    wallpaper: ImageBitmap?,
    bgBlur: Dp,
    modifier: Modifier = Modifier,
) {
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
                        // 0 就别挂 blur 了 —— 挂一个 0dp 的 RenderEffect 也要走一趟离屏
                        if (bgBlur > 0.dp) {
                            Modifier.blur(bgBlur, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                        } else {
                            Modifier
                        }
                    ),
            )
        }
    }
}

/**
 * 背景上面那两层纱：压黑 + 导轨那条渐变。
 *
 * **必须画在 layerBackdrop 外面** —— 纱是要给眼睛看的，不该被卡片折射进去
 * （折进去就是老注释说的"一团黑"）。
 *
 * @param fullScrim 有内容页底图的时候要传 false：底图把壁纸盖住了，
 *   只有导轨那一条还露着壁纸，所以压黑也只需要压那一条。
 *   （原来那版压黑纱是画在底图**下面**的，效果一样，这里只是把顺序翻过来）
 */
@Composable
fun HomeSceneDecor(
    railWidth: Dp,
    fullScrim: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeModeState.current.isDark
    val scrim = if (isDark) 0.42f else 0.08f
    val railAlphaTop = if (isDark) 0.60f else 0.22f
    val railAlphaMid = if (isDark) 0.44f else 0.14f

    Box(modifier.fillMaxSize()) {
        // 底纱（浅色几乎透明，颜色留住；深色才压重）
        Box(
            Modifier
                .then(if (fullScrim) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(railWidth))
                .background(Color.Black.copy(alpha = scrim))
        )

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
