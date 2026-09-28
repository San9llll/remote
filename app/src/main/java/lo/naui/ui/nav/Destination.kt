package lo.naui.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Settings

/** 一级页面。图标顺序对齐 Aster：Home / Layers / GridView / Settings */
enum class Dest(val label: String, val icon: ImageVector) {
    Home("主页", MiuixIcons.Home),
    Modules("功能", MiuixIcons.Layers),
    Overview("概览", MiuixIcons.GridView),
    Settings("设置", MiuixIcons.Settings),
}
