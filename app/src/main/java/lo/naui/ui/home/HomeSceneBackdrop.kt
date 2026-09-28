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
 * 全景首页的整页背景 —— 壁纸模糊后直接铺满，导轨那一条用**主题色**轻轻压一层。
 *
 * 要点（上一版的教训）：
 *  - 黑纱别压太狠。上一版浅色模式也压 0.28，再加导轨条 0.42 纯黑，
 *    结果整个界面发灰、壁纸的颜色全丢了。现在浅色模式几乎不压，颜色留住；
 *  - 深色模式才真正加深（壁纸太亮的话，深色 UI 压不住）；
 *  - 导轨那条用 surface（带主题色调）而不是纯黑 —— 纯黑出来是灰。
 */
@Composable
fun HomeSceneBackdrop(
    wallpaper: ImageBitmap?,
    railWidth: Dp,
    /**
     * true = 这层放**清晰**壁纸，模糊交给玻璃卡里的 drawBackdrop 去做。
     *
     * 液态玻璃模式必须这样：backdrop 抓的是**这一层已经画好的像素**，
     * 如果这层自己先糊了一遍又压了一层黑纱，玻璃卡折射到的就是一团黑 ——
     * 之前"读到的不是选的图，而是黑底"就是这么来的。
     */
    sharp: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeModeState.current.isDark
    // 底纱：浅色几乎不压，深色压重；清晰模式再轻一档，别把颜色压没了
    val scrim = when {
        sharp && isDark -> 0.26f
        sharp -> 0.04f
        isDark -> 0.42f
        else -> 0.08f
    }
    // 导轨条：用主题的面板色，浅色下轻轻一层保住白字可读
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
            // 壁纸模糊 —— 这是 Aster 原版的用法（HomeSceneBackdrop 里就是 blur 32dp），
            // 之前我把它当嫌疑人摘掉，是冤枉它了，现在恢复。
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (sharp) {
                            Modifier
                        } else {
                            Modifier.blur(32.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                        }
                    ),
            )
        }

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
