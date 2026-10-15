package lo.naui.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 明暗模式（对应 Aster 的 ColorSchemeMode.MonetSystem/Light/Dark） */
enum class DarkMode(val id: String, val label: String, val summary: String) {
    System("system", "跟随系统", "根据系统的深色模式自动切换"),
    Light("light", "浅色", "始终亮色"),
    Dark("dark", "深色", "始终暗色");

    fun toMode(systemDark: Boolean): ColorSchemeMode = when (this) {
        System -> if (systemDark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
        Light -> ColorSchemeMode.MonetLight
        Dark -> ColorSchemeMode.MonetDark
    }

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: System }
}

/**
 * 主题外壳 —— 对齐 Aster 的 APatchTheme。
 * 把「明暗 + 基准色 + 配色方案 + 配色规范」交给 Miuix 的 ThemeController，
 * 由它按 Material You 规则展开整套配色。
 */
@Composable
fun NakourTheme(
    prefs: ThemePrefs,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val mode = prefs.darkMode.toMode(systemDark)

    val controller = remember(
        mode, prefs.effectiveKeyColor, prefs.paletteStyle, prefs.effectiveSpec
    ) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = Color(prefs.effectiveKeyColor),
            colorSpec = prefs.effectiveSpec,
            paletteStyle = prefs.paletteStyle,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(systemDark) {
            val w = (view.context as Activity).window
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !systemDark
            c.isAppearanceLightNavigationBars = !systemDark
            onDispose { }
        }
    }

    // 排查启动闪退期间：不做任何密度干预，也不包 backdrop 层
    CompositionLocalProvider(
        LocalThemeModeState provides ThemeModeState(
            isDark = mode == ColorSchemeMode.MonetDark,
            isDynamicColor = true,
        )
    ) {
        MiuixTheme(controller = controller) {
            content()
        }
    }
}
