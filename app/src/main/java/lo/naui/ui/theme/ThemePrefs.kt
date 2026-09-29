package lo.naui.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** 预设基准色（对齐 Aster 的 LegacyMiuixThemeSeeds，色值一致） */
object PresetColors {
    val list: List<Triple<String, String, Int>> = listOf(
        Triple("blue", "蓝", 0xFF2196F3.toInt()),
        Triple("sakura", "樱花", 0xFFE88AA6.toInt()),
        Triple("purple", "紫", 0xFF9C27B0.toInt()),
        Triple("deep_purple", "深紫", 0xFF673AB7.toInt()),
        Triple("indigo", "靛蓝", 0xFF3F51B5.toInt()),
        Triple("pink", "粉", 0xFFE91E63.toInt()),
        Triple("red", "红", 0xFFF44336.toInt()),
        Triple("deep_orange", "深橙", 0xFFFF5722.toInt()),
        Triple("orange", "橙", 0xFFFF9800.toInt()),
        Triple("amber", "琥珀", 0xFFFFC107.toInt()),
        Triple("yellow", "黄", 0xFFFFD600.toInt()),
        Triple("lime", "柠檬", 0xFFCDDC39.toInt()),
        Triple("light_green", "浅绿", 0xFF8BC34A.toInt()),
        Triple("green", "绿", 0xFF4CAF50.toInt()),
        Triple("teal", "青绿", 0xFF009688.toInt()),
        Triple("cyan", "青", 0xFF00BCD4.toInt()),
        Triple("light_blue", "浅蓝", 0xFF03A9F4.toInt()),
        Triple("blue_grey", "蓝灰", 0xFF607D8B.toInt()),
        Triple("brown", "棕", 0xFF795548.toInt()),
    )

    fun seedOf(name: String?): Int = list.firstOrNull { it.first == name }?.third ?: 0xFF2196F3.toInt()
    fun labelOf(name: String?): String = list.firstOrNull { it.first == name }?.second ?: "蓝"
}

/** 配色方案的说明文案（对齐 Aster） */
fun ThemePaletteStyle.summary(): String = when (this) {
    ThemePaletteStyle.TonalSpot -> "由系统把种子色均匀铺到整套配色上"
    ThemePaletteStyle.Neutral -> "近乎无色，只给需要留意的地方上色"
    ThemePaletteStyle.Vibrant -> "把种子色的饱和度拉到最高，观感强烈"
    ThemePaletteStyle.Expressive -> "拉开色相与明暗，各层界面区分明显"
    ThemePaletteStyle.Rainbow -> "让点缀色沿色轮铺开，不再只有一种颜色"
    ThemePaletteStyle.FruitSalad -> "同时使用多种色相，是其中最鲜艳的一种"
    ThemePaletteStyle.Monochrome -> "只保留一种色调，颜色本身不承载含义"
    ThemePaletteStyle.Fidelity -> "更贴近原始种子色，减少额外调色"
    ThemePaletteStyle.Content -> "点缀色取自种子色本身，而非固定模型"
    else -> ""
}

/** 配色方案的显示名 */
fun ThemePaletteStyle.label(): String = when (this) {
    ThemePaletteStyle.TonalSpot -> "柔和"
    ThemePaletteStyle.Neutral -> "中性"
    ThemePaletteStyle.Vibrant -> "鲜艳"
    ThemePaletteStyle.Expressive -> "表现力"
    ThemePaletteStyle.Rainbow -> "彩虹"
    ThemePaletteStyle.FruitSalad -> "果盘"
    ThemePaletteStyle.Monochrome -> "单色"
    ThemePaletteStyle.Fidelity -> "保真"
    ThemePaletteStyle.Content -> "内容"
    else -> name
}

/** 首页布局 / 全局布局 / 导航 / 时钟 / 密度（对齐 Aster 的枚举值） */
enum class HomeLayout(val id: String, val label: String, val summary: String) {
    Large("large", "大卡片", "状态是一张大卡，模块数量摞在它右边。"),
    Compact("compact", "单行", "状态横跨整页，工作模式在卡片下沿。"),
    List("list", "列表", "状态排成一行，动作按钮放在行末。");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Large }
}

enum class GlobalLayout(val id: String, val label: String, val summary: String) {
    Panorama("panorama", "全景首页", "首页是壁纸场景，其他页面使用悬浮导航。"),
    Standard("standard", "标准首页", "所有页面使用传统的底部导航或侧栏导航。");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Panorama }
}

enum class NavMode(val id: String, val label: String, val summary: String) {
    Auto("auto", "自动适配", "窄屏使用底部导航，宽屏使用侧栏导航。"),
    Bottom("bottom", "底部导航", "固定在底部的导航栏"),
    Sidebar("sidebar", "侧栏导航", "固定在左侧的导航栏");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Auto }
}

enum class ClockStyle(val id: String, val label: String, val summary: String) {
    Stacked("stacked", "两行数字", "小时在上、分钟在下。"),
    Inline("inline", "单行数字", "一行时间，下面一行日期。"),
    Analog("analog", "指针式", "用表盘代替数字。"),
    Date("date", "仅日期", "只显示星期和日期，不显示时间。");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Stacked }
}

enum class Density(val id: String, val label: String, val summary: String, val dpi: Int) {
    Compact("compact", "紧凑", "更小更密", 380),
    Standard("standard", "标准", "默认间距", 420),
    Cozy("cozy", "舒适", "更大更松", 480);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Standard
        fun byDpi(dpi: Int) = entries.minByOrNull { kotlin.math.abs(it.dpi - dpi) } ?: Standard
    }
}

/** 卡片方案：默认还是原来那套仿玻璃（纯 2D，最稳），液态玻璃是可选项 */
enum class CardStyle(val id: String, val label: String, val summary: String) {
    Legacy("legacy", "仿玻璃", "纯 2D 叠层，零 GPU 折射 —— 默认，最不容易出岔子"),
    Liquid("liquid", "液态玻璃", "走社区库做真折射，观感更好，但吃 GPU");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Legacy }
}

/** 侧栏信息块里一行能显示什么 */
enum class InfoMetric(val id: String, val label: String, val summary: String) {
    None("none", "不显示", "这一行留空"),
    BatteryTemp("battery_temp", "电池温度", "电池的摄氏温度，公开 API 就能拿"),
    CpuTemp("cpu_temp", "CPU 温度", "热区里挑一个像 CPU / SOC 的"),
    Ram("ram", "内存占用", "已用 / 总内存的百分比"),
    CpuUsage("cpu_usage", "CPU 使用率", "/proc/stat 两次采样的差值"),
    GpuUsage("gpu_usage", "GPU 占用率", "要看 GPU 性能节点，多半得 root"),
    BatteryPower("battery_power", "电池功率", "充电为正、放电为负"),
    BatteryVi("battery_vi", "电压和电流", "电压 V + 电流 A，窄栏里拆两行写"),
    Network("network", "网络", "运营商名称 + 实时上下行速率");

    /** 窄导轨里一行放不下，改成上下两行 */
    val wide: Boolean get() = this == BatteryVi || this == Network

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: None }
}

/** 侧栏信息块最多几行 */
const val INFO_LINE_COUNT = 5

/** 侧栏信息块的出厂内容 */
const val DEFAULT_RAIL_INFO_LINES = "battery_temp,cpu_temp,ram,none,none"

/** 当前密度对应的 dpi 数值（滑块用） */
fun Density.densityOf(): Int = dpi

object Prefs {
    @Volatile
    var current: ThemePrefs? = null
}

/** 主题设置，存 SharedPreferences */
class ThemePrefs(context: Context) {
    private val sp = context.getSharedPreferences("nakour_theme", Context.MODE_PRIVATE)

    init {
        WallpaperColorTheme.load(sp)
    }

    var darkMode by mutableStateOf(DarkMode.of(sp.getString("dark", null)))
    var paletteStyle by mutableStateOf(
        try { ThemePaletteStyle.valueOf(sp.getString("style", null) ?: "TonalSpot") }
        catch (e: Exception) { ThemePaletteStyle.TonalSpot }
    )
    var colorSpec by mutableStateOf(
        try { ThemeColorSpec.valueOf(sp.getString("spec", null) ?: "Spec2021") }
        catch (e: Exception) { ThemeColorSpec.Spec2021 }
    )
    var presetName by mutableStateOf(sp.getString("preset", "blue") ?: "blue")
    var customSeed by mutableStateOf(sp.getInt("seed", 0))
    var useCustomSeed by mutableStateOf(sp.getBoolean("useCustom", false))

    var homeLayout by mutableStateOf(HomeLayout.of(sp.getString("home_layout", null)))
    var globalLayout by mutableStateOf(GlobalLayout.of(sp.getString("global_layout", null)))
    var navMode by mutableStateOf(NavMode.of(sp.getString("nav_mode", null)))
    var clockStyle by mutableStateOf(ClockStyle.of(sp.getString("clock_style", null)))
    var density by mutableStateOf(Density.of(sp.getString("density", null)))

    val keyColor: Int
        get() = if (useCustomSeed && customSeed != 0) customSeed else PresetColors.seedOf(presetName)

    /**
     * 真正拿去建调色板的种子：跟随壁纸开着、而且已经取到色的时候，
     * 壁纸比预设色更具体，所以它优先（对齐 Aster 的来源优先级）。
     */
    val effectiveKeyColor: Int
        get() = if (WallpaperColorTheme.enabled && WallpaperColorTheme.seed != 0) {
            WallpaperColorTheme.seed
        } else {
            keyColor
        }

    /** 2025 规范只对部分方案有效，不支持时退回 2021（和 Aster 一致） */
    val effectiveSpec: ThemeColorSpec
        get() = if (colorSpec == ThemeColorSpec.Spec2025 && !paletteStyle.supportsSpec2025())
            ThemeColorSpec.Spec2021 else colorSpec

    private fun ThemePaletteStyle.supportsSpec2025(): Boolean =
        this in setOf(
            ThemePaletteStyle.TonalSpot, ThemePaletteStyle.Neutral,
            ThemePaletteStyle.Vibrant, ThemePaletteStyle.Expressive,
            ThemePaletteStyle.Monochrome,
        )

    fun updateDarkMode(v: DarkMode) { darkMode = v; sp.edit().putString("dark", v.id).apply() }
    fun updateStyle(v: ThemePaletteStyle) { paletteStyle = v; sp.edit().putString("style", v.name).apply() }
    fun updateSpec(v: ThemeColorSpec) { colorSpec = v; sp.edit().putString("spec", v.name).apply() }
    fun updatePreset(name: String) {
        presetName = name; useCustomSeed = false
        sp.edit().putString("preset", name).putBoolean("useCustom", false).apply()
    }
    fun updateCustomSeed(color: Color) {
        customSeed = color.toArgb(); useCustomSeed = true
        sp.edit().putInt("seed", customSeed).putBoolean("useCustom", true).apply()
    }
    fun updateHomeLayout(v: HomeLayout) { homeLayout = v; sp.edit().putString("home_layout", v.id).apply() }
    fun updateGlobalLayout(v: GlobalLayout) { globalLayout = v; sp.edit().putString("global_layout", v.id).apply() }
    fun updateNavMode(v: NavMode) { navMode = v; sp.edit().putString("nav_mode", v.id).apply() }
    fun updateClockStyle(v: ClockStyle) { clockStyle = v; sp.edit().putString("clock_style", v.id).apply() }
    fun updateDensity(v: Density) { density = v; sp.edit().putString("density", v.id).apply() }

    // 应用密度：真正去改 Compose 的 density，不只是记个名字
    var customDpi by mutableStateOf(sp.getInt("density_dpi", 0))

    fun updateDensityDpi(dpi: Int) {
        customDpi = dpi
        val matched = Density.byDpi(dpi)
        density = matched
        sp.edit().putInt("density_dpi", dpi).putString("density", matched.id).apply()
    }

    /** 当前生效的 dpi（0 = 跟随系统） */
    val effectiveDpi: Int get() = if (customDpi > 0) customDpi else density.dpi

    /** 开关拇指里要不要放状态图标（对齐 Aster 的「开关指示器」） */
    var switchIndicator by mutableStateOf(sp.getBoolean("switch_indicator", true))

    fun updateSwitchIndicator(v: Boolean) {
        switchIndicator = v
        sp.edit().putBoolean("switch_indicator", v).apply()
    }

    /** 跟随壁纸取色（全景首页那张照片的颜色当种子） */
    var followWallpaper by mutableStateOf(WallpaperColorTheme.enabled)

    fun updateFollowWallpaper(v: Boolean) {
        followWallpaper = v
        WallpaperColorTheme.setEnabled(sp, v)
    }

    /** 主图（本地图片的绝对路径，空 = 用兜底渐变） */
    var homeImage by mutableStateOf(sp.getString("home_image", "") ?: "")

    fun updateHomeImage(path: String) {
        homeImage = path
        sp.edit().putString("home_image", path).apply()
    }

    /** 内容页背景（模块 / 概览 / 设置这些页面铺的那张，不影响主页和侧边栏） */
    var contentImage by mutableStateOf(sp.getString("content_image", "") ?: "")

    fun updateContentImage(path: String) {
        contentImage = path
        sp.edit().putString("content_image", path).apply()
    }

    /**
     * 页面背景图 —— 给「模块 / 概览 / 设置」三页铺底。
     * 主页和侧边栏都不受影响（它们有自己那套）。
     */
    var pageImage by mutableStateOf(sp.getString("page_image", "") ?: "")

    fun updatePageImage(path: String) {
        pageImage = path
        sp.edit().putString("page_image", path).apply()
    }

    /** 首页拉到壁纸后，把算出来的种子存下来给主题用 */
    fun saveWallpaperSeed(seed: Int, isLight: Boolean) {
        WallpaperColorTheme.setSeed(sp, seed, isLight)
    }

    /* ---------- 卡片方案 ---------- */

    var cardStyle by mutableStateOf(CardStyle.of(sp.getString("card_style", null)))

    fun updateCardStyle(v: CardStyle) {
        cardStyle = v
        sp.edit().putString("card_style", v.id).apply()
    }

    /* ---------- 侧栏 ---------- */

    /** 导轨上那颗电池 */
    var railShowBattery by mutableStateOf(sp.getBoolean("rail_battery", true))

    fun updateRailShowBattery(v: Boolean) {
        railShowBattery = v
        sp.edit().putBoolean("rail_battery", v).apply()
    }

    /** 导轨上那个信息块 */
    var railShowInfo by mutableStateOf(sp.getBoolean("rail_info", true))

    fun updateRailShowInfo(v: Boolean) {
        railShowInfo = v
        sp.edit().putBoolean("rail_info", v).apply()
    }

    /** 导轨上「概览」那个入口 */
    var railShowOverview by mutableStateOf(sp.getBoolean("rail_overview", true))

    fun updateRailShowOverview(v: Boolean) {
        railShowOverview = v
        sp.edit().putBoolean("rail_overview", v).apply()
    }

    /* ---------- 玻璃参数（液态玻璃那几个旋钮）---------- */

    /** 模糊半径，dp */
    var glassBlur by mutableStateOf(sp.getFloat("glass_blur", 20f))

    fun updateGlassBlur(v: Float) {
        glassBlur = v.coerceIn(0f, 40f)
        sp.edit().putFloat("glass_blur", glassBlur).apply()
    }

    /** 折射深度（lens），dp */
    var glassLens by mutableStateOf(sp.getFloat("glass_lens", 12f))

    fun updateGlassLens(v: Float) {
        glassLens = v.coerceIn(0f, 40f)
        sp.edit().putFloat("glass_lens", glassLens).apply()
    }

    /** 卡片面板色的不透明度 */
    var glassAlpha by mutableStateOf(sp.getFloat("glass_alpha", 0.58f))

    fun updateGlassAlpha(v: Float) {
        glassAlpha = v.coerceIn(0.10f, 0.95f)
        sp.edit().putFloat("glass_alpha", glassAlpha).apply()
    }

    /** 卡片圆角，dp */
    var glassRadius by mutableStateOf(sp.getInt("glass_radius", 20))

    fun updateGlassRadius(v: Int) {
        glassRadius = v.coerceIn(0, 36)
        sp.edit().putInt("glass_radius", glassRadius).apply()
    }

    /** 信息块的 5 行内容（存成逗号分隔的 id） */
    var railInfoLines by mutableStateOf(
        sp.getString("rail_info_lines", DEFAULT_RAIL_INFO_LINES) ?: DEFAULT_RAIL_INFO_LINES
    )

    /** 永远返回 5 个槽位，缺的补「不显示」 */
    fun railInfoList(): List<InfoMetric> {
        val parts = railInfoLines.split(",")
        return (0 until INFO_LINE_COUNT).map { InfoMetric.of(parts.getOrNull(it)) }
    }

    fun updateRailInfoAt(index: Int, metric: InfoMetric) {
        if (index !in 0 until INFO_LINE_COUNT) return
        val list = railInfoList().toMutableList()
        list[index] = metric
        railInfoLines = list.joinToString(",") { it.id }
        sp.edit().putString("rail_info_lines", railInfoLines).apply()
    }
}
