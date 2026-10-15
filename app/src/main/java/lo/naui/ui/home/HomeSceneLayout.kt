// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class HomeSceneLayout(
    val across: Boolean,
    val heroHeight: Dp,
)

/** 用场景导轨和系统栏之外剩下的空间来排版，分屏时也一样。 */
fun homeSceneLayout(width: Dp, height: Dp): HomeSceneLayout = HomeSceneLayout(
    // 横屏平板跟横屏手机一样要分成两栏。
    across = width > height,
    // 把状态卡和后面的卡留在触手可及的位置：不设上限的话，
    // 横屏壁纸会变成一条被裁得很惨的窄横幅。
    heroHeight = (height * if (height < 480.dp) 0.50f else 0.63f)
        .coerceIn(300.dp, 520.dp),
)
