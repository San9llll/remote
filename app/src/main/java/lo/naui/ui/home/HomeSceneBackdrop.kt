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
 * 而且只录**一遍**，同时满足两边要的东西不一样：
 *
 * ```
 * ① [layerBackdrop 里]  兜底渐变 + 壁纸 + 涟漪          ← 清晰，一点不糊
 * ② [采样层之上]        把 ① 录下来的那层 drawPlainBackdrop + blur 重放   ← 眼睛看的糊背景
 * ③ [再上面]            压黑纱 + 导轨渐变（HomeSceneDecor）
 * ④ [最上面]            内容层，卡片 drawBackdrop 采 ① 的**清晰**原图
 * ```
 *
 * 为什么 ① 必须清晰：lens 折的是**细节**，喂它一张糊过的图就什么都折不出来 ——
 * 1.00.0 第一版就是卡片采的糊背景，用户看到的成品就是"液态玻璃没生效"。
 * 而眼睛要的是糊背景，所以 ② 用同一份录制重放一遍 + 一次模糊，
 * **不重画位图**：全屏壁纸自始至终只画一次。
 * 纱放 ③ 同理 —— 折进卡片就是一团黑。
 */
@Composable
fun HomeSceneBackdrop(
    wallpaper: ImageBitmap?,
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
                // ⚠️ 这儿**不加模糊**：这一张是给卡片折射用的素材，糊了就没细节可折。
                // 眼睛看到的糊版本由外壳拿这份录制重放一遍 + 模糊得到（AppShell 的 ② 层）。
                modifier = Modifier.fillMaxSize(),
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
