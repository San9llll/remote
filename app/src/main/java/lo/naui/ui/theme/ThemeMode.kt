package lo.naui.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Resolved theme facts for screens that need the app's own dark / monet
 * decision instead of the raw system configuration.
 *
 * （对齐 Aster 的 ThemeModeState）
 */
@Immutable
data class ThemeModeState(
    val isDark: Boolean,
    val isDynamicColor: Boolean,
)

val LocalThemeModeState = compositionLocalOf { ThemeModeState(isDark = false, isDynamicColor = true) }
