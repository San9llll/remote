package lo.naui.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.common.Option
import lo.naui.ui.common.OptionDialog
import lo.naui.ui.common.SectionTitle
import lo.naui.ui.component.IndicatorSwitchPreference
import lo.naui.ui.theme.CardStyle
import lo.naui.ui.theme.ClockStyle
import lo.naui.ui.theme.DarkMode
import lo.naui.ui.theme.Density
import lo.naui.ui.theme.GlobalLayout
import lo.naui.ui.theme.HomeLayout
import lo.naui.ui.theme.LocalThemeModeState
import lo.naui.ui.theme.NavMode
import lo.naui.ui.theme.PresetColors
import lo.naui.ui.theme.ThemePrefs
import lo.naui.ui.theme.label
import lo.naui.ui.theme.summary
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.ZoomOut
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.overScrollVertical
import lo.naui.ui.theme.densityOf
import kotlin.math.roundToInt

private val PresetDot = 20.dp
private val StyleDotSize = 12.dp
private val StyleDotGap = 4.dp

/**
 * 主题设置 —— 所有跟长相有关的都在这一页：
 * 预览 / 配色（方案 · 规范 · 基准色 · 跟随壁纸）/ 明暗 / 密度 / 导航与外壳 / 开关。
 *
 * 设置页只留一个入口指向这里，免得主设置列表被这些散项撑乱。
 */
@Composable
fun ThemeScreen(prefs: ThemePrefs, onBack: () -> Unit = {}) {
    // 侧栏设置是这一页里的子页，不占 AppShell 的一级导航
    var railPage by remember { mutableStateOf(false) }
    if (railPage) {
        RailSettingsScreen(prefs = prefs, onBack = { railPage = false })
        return
    }

    val isDark = LocalThemeModeState.current.isDark
    val seed = prefs.keyColor
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val contentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val target = java.io.File(ctx.filesDir, "content_image.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.updateContentImage(target.absolutePath)
            }
        }
    }


    var dialog by remember { mutableStateOf<String?>(null) }
    // 主页大图：系统选择器挑一张 → 复制进 App 私有目录（本地图，不走服务器）
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val target = java.io.File(ctx.filesDir, "home_image.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.updateHomeImage(target.absolutePath)
            }
        }
    }
    // 页面背景（模块 / 概览 / 设置 三页用，不影响主页和侧边栏）
    val pagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val target = java.io.File(ctx.filesDir, "page_bg.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.updatePageImage(target.absolutePath)
            }
        }
    }
    // 密度滑块：拖动时先改这个草稿值
    var densityDraft by remember { mutableStateOf(prefs.density.densityOf()) }
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "主题",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item(key = "preview") {
                SectionTitle("预览")
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    ThemeColorPreviewCard(
                        seed = seed,
                        style = prefs.paletteStyle,
                        spec = prefs.effectiveSpec,
                        title = prefs.paletteStyle.label(),
                        summary = prefs.paletteStyle.summary(),
                        dark = isDark,
                    )
                }
            }

            item(key = "color") {
                SectionTitle("配色")
                SettingsCard {
                    val styles = ThemePaletteStyle.entries
                    OverlayDropdownPreference(
                        title = "配色方案",
                        summary = prefs.paletteStyle.summary(),
                        items = styles.map { it.name },
                        selectedIndex = styles.indexOf(prefs.paletteStyle).coerceAtLeast(0),
                        startAction = { SettingsIcon(MiuixIcons.Layers) },
                        onSelectedIndexChange = { i -> prefs.updateStyle(styles[i]) },
                    )

                    val specs = ThemeColorSpec.entries
                    OverlayDropdownPreference(
                        title = "配色规范",
                        summary = if (prefs.colorSpec == ThemeColorSpec.Spec2025) {
                            "M3 Expressive 2025（只对四种方案有效，其余退回 2021）"
                        } else {
                            "M3 2021 色调规则"
                        },
                        items = specs.map {
                            if (it == ThemeColorSpec.Spec2025) "M3 Expressive 2025" else "M3 2021"
                        },
                        selectedIndex = specs.indexOf(prefs.colorSpec).coerceAtLeast(0),
                        startAction = { SettingsIcon(MiuixIcons.Tune) },
                        onSelectedIndexChange = { i -> prefs.updateSpec(specs[i]) },
                    )

                    IndicatorSwitchPreference(
                        checked = prefs.followWallpaper,
                        onCheckedChange = { prefs.updateFollowWallpaper(it) },
                        title = "跟随壁纸取色",
                        summary = "从首页那张照片里取种子色",
                    )
                }
            }

            item(key = "card") {
                SectionTitle("卡片")
                SettingsCard {
                    ArrowPreference(
                        title = "卡片风格",
                        summary = prefs.cardStyle.summary,
                        startAction = { SettingsIcon(MiuixIcons.Layers) },
                        onClick = { dialog = "cardstyle" },
                    )
                }
            }

            item(key = "glass") {
                SectionTitle("玻璃参数")
                SettingsCard {
                    ArrowPreference(
                        title = "当前方案",
                        summary = if (prefs.cardStyle == CardStyle.Liquid) {
                            "液态玻璃 · 下面这几个数当场生效"
                        } else {
                            "仿玻璃 · 想调折射就先切到液态玻璃（模糊和圆角两种方案都吃）"
                        },
                        onClick = { dialog = "cardstyle" },
                    )
                    SliderPreference(
                        value = prefs.glassBlur,
                        onValueChange = { prefs.updateGlassBlur(it) },
                        title = "模糊",
                        summary = "背景透过玻璃被糊掉的程度",
                        valueText = prefs.glassBlur.toInt().toString() + " dp",
                        valueRange = 0f..40f,
                        steps = 7,
                    )
                    SliderPreference(
                        value = prefs.glassLens,
                        onValueChange = { prefs.updateGlassLens(it) },
                        title = "折射深度",
                        summary = "边缘那道液态折射的强度（只对液态玻璃有效）",
                        valueText = prefs.glassLens.toInt().toString() + " dp",
                        valueRange = 0f..40f,
                        steps = 7,
                    )
                    SliderPreference(
                        value = prefs.glassAlpha,
                        onValueChange = { prefs.updateGlassAlpha(it) },
                        title = "面板不透明度",
                        summary = "越小越透，越大字越清楚",
                        valueText = String.format("%.2f", prefs.glassAlpha),
                        valueRange = 0.10f..0.95f,
                        steps = 16,
                    )
                    SliderPreference(
                        value = prefs.glassRadius.toFloat(),
                        onValueChange = { prefs.updateGlassRadius(it.toInt()) },
                        title = "卡片圆角",
                        summary = "所有玻璃卡共用这个圆角",
                        valueText = prefs.glassRadius.toString() + " dp",
                        valueRange = 0f..36f,
                        steps = 11,
                    )
                }
            }

            item(key = "presets") {
                SectionTitle("基准色（" + PresetColors.list.size + " 种）")
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        PresetColors.list.chunked(5).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { (id, name, color) ->
                                    val on = prefs.presetName == id && !prefs.useCustomSeed
                                    Column(
                                        Modifier.weight(1f).clickable { prefs.updatePreset(id) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            Modifier.size(PresetDot).clip(CircleShape).background(Color(color))
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            name, fontSize = 10.sp,
                                            color = if (on) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            item(key = "dark") {
                SectionTitle("明暗与密度")
                SettingsCard {
                    ArrowPreference(
                        title = "明暗",
                        summary = prefs.darkMode.summary,
                        onClick = { dialog = "dark" },
                    )
                    SliderPreference(
                        value = densityDraft.toFloat(),
                        onValueChange = { densityDraft = it.roundToInt() },
                        title = "应用密度",
                        summary = "数值越大，界面元素越大",
                        valueText = densityDraft.toString() + " dpi",
                        startAction = { SettingsIcon(MiuixIcons.ZoomOut) },
                        valueRange = 320f..600f,
                        steps = 5,
                    )
                }
            }

            item(key = "shell") {
                SectionTitle("导航与外壳")
                SettingsCard {
                    ArrowPreference(
                        title = "全局布局",
                        summary = prefs.globalLayout.summary,
                        startAction = { SettingsIcon(MiuixIcons.ScreenMirroring) },
                        onClick = { dialog = "global" },
                    )
                    ArrowPreference(
                        title = "首页布局",
                        summary = prefs.homeLayout.summary,
                        startAction = { SettingsIcon(MiuixIcons.Tune) },
                        onClick = { dialog = "home" },
                    )
                    ArrowPreference(
                        title = "导航布局",
                        summary = prefs.navMode.summary,
                        startAction = { SettingsIcon(MiuixIcons.Refresh) },
                        onClick = { dialog = "nav" },
                    )
                    ArrowPreference(
                        title = "时钟样式",
                        summary = prefs.clockStyle.summary,
                        startAction = { SettingsIcon(MiuixIcons.Notes) },
                        onClick = { dialog = "clock" },
                    )
                    ArrowPreference(
                        title = "侧栏设置",
                        summary = if (prefs.railShowOverview) {
                            "侧栏模块 · 信息内容（概览入口开着）"
                        } else {
                            "侧栏模块 · 信息内容（概览入口已隐藏）"
                        },
                        startAction = { SettingsIcon(MiuixIcons.Tune) },
                        onClick = { railPage = true },
                    )
                }
            }

            item(key = "background") {
                SectionTitle("背景")
                SettingsCard {
                    ArrowPreference(
                        title = "主页大图",
                        summary = if (prefs.homeImage.isBlank()) {
                            "还没选（用主题渐变兜底）"
                        } else {
                            "已设置 · 点一下换一张"
                        },
                        startAction = { SettingsIcon(MiuixIcons.Photos) },
                        onClick = { imagePicker.launch("image/*") },
                    )
                    if (prefs.homeImage.isNotBlank()) {
                        ArrowPreference(
                            title = "清除主页大图",
                            summary = "回到主题渐变兜底",
                            onClick = { prefs.updateHomeImage("") },
                        )
                    }

                    ArrowPreference(
                        title = "内容页背景",
                        summary = if (prefs.contentImage.isBlank()) {
                            "还没选（用主题底色）"
                        } else {
                            "已设置 · 点一下换一张"
                        },
                        startAction = { SettingsIcon(MiuixIcons.Photos) },
                        onClick = { contentPicker.launch("image/*") },
                    )
                    if (prefs.contentImage.isNotBlank()) {
                        ArrowPreference(
                            title = "清除内容页背景",
                            summary = "回到主题底色",
                            onClick = { prefs.updateContentImage("") },
                        )
                    }

                    ArrowPreference(
                        title = "页面背景",
                        summary = if (prefs.pageImage.isBlank()) {
                            "还没选 · 会用在「功能 / Agent / 设置」三页"
                        } else {
                            "已设置 · 用在「功能 / Agent / 设置」三页"
                        },
                        startAction = { SettingsIcon(MiuixIcons.Photos) },
                        onClick = { pagePicker.launch("image/*") },
                    )
                    if (prefs.pageImage.isNotBlank()) {
                        ArrowPreference(
                            title = "清除页面背景",
                            summary = "回到纯主题底色",
                            onClick = { prefs.updatePageImage("") },
                        )
                    }

                    ArrowPreference(
                        title = "说明",
                        summary = "主页大图会糊一层做背景；玻璃卡折射的是它没糊的那份",
                    )
                }
            }

            item(key = "switches") {
                SectionTitle("开关")
                SettingsCard {
                    IndicatorSwitchPreference(
                        checked = prefs.switchIndicator,
                        onCheckedChange = { prefs.updateSwitchIndicator(it) },
                        title = "开关图标",
                        summary = "在开关的拇指里显示 Ok / Close",
                    )
                    IndicatorSwitchPreference(
                        checked = prefs.globalLayout == GlobalLayout.Panorama,
                        onCheckedChange = {
                            prefs.updateGlobalLayout(if (it) GlobalLayout.Panorama else GlobalLayout.Standard)
                        },
                        title = "全景首页",
                        summary = "首页用壁纸场景 + 左侧场景导轨",
                    )
                }
            }
        }
    }

    when (dialog) {
        "style" -> OptionDialog(
            show = true, title = "配色方案",
            options = ThemePaletteStyle.entries.map { Option(it.name, it.name, it.summary()) },
            currentId = prefs.paletteStyle.name,
            onPick = { id -> ThemePaletteStyle.entries.firstOrNull { it.name == id }?.let { prefs.updateStyle(it) } },
            onDismiss = { dialog = null },
        )
        "spec" -> OptionDialog(
            show = true, title = "配色规范",
            options = ThemeColorSpec.entries.map {
                Option(
                    it.name,
                    if (it == ThemeColorSpec.Spec2025) "M3 Expressive 2025" else "M3 2021",
                    if (it == ThemeColorSpec.Spec2025) "只对四种方案有效，其余退回 2021" else "2021 年的色调规则",
                )
            },
            currentId = prefs.colorSpec.name,
            onPick = { id -> ThemeColorSpec.entries.firstOrNull { it.name == id }?.let { prefs.updateSpec(it) } },
            onDismiss = { dialog = null },
        )
        "dark" -> OptionDialog(
            show = true, title = "明暗",
            options = DarkMode.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.darkMode.id,
            onPick = { id -> DarkMode.entries.firstOrNull { it.id == id }?.let { prefs.updateDarkMode(it) } },
            onDismiss = { dialog = null },
        )
        "global" -> OptionDialog(
            show = true, title = "全局布局",
            options = GlobalLayout.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.globalLayout.id,
            onPick = { id -> GlobalLayout.entries.firstOrNull { it.id == id }?.let { prefs.updateGlobalLayout(it) } },
            onDismiss = { dialog = null },
        )
        "home" -> OptionDialog(
            show = true, title = "首页布局",
            options = HomeLayout.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.homeLayout.id,
            onPick = { id -> HomeLayout.entries.firstOrNull { it.id == id }?.let { prefs.updateHomeLayout(it) } },
            onDismiss = { dialog = null },
        )
        "nav" -> OptionDialog(
            show = true, title = "导航布局",
            options = NavMode.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.navMode.id,
            onPick = { id -> NavMode.entries.firstOrNull { it.id == id }?.let { prefs.updateNavMode(it) } },
            onDismiss = { dialog = null },
        )
        "clock" -> OptionDialog(
            show = true, title = "时钟样式",
            options = ClockStyle.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.clockStyle.id,
            onPick = { id -> ClockStyle.entries.firstOrNull { it.id == id }?.let { prefs.updateClockStyle(it) } },
            onDismiss = { dialog = null },
        )
        "cardstyle" -> OptionDialog(
            show = true, title = "卡片风格",
            options = CardStyle.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.cardStyle.id,
            onPick = { id -> prefs.updateCardStyle(CardStyle.of(id)) },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 12.dp),
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column { content() }
    }
}

/** 预览卡：嵌一层主题，用真正那套配色把自己画出来 */
@Composable
private fun ThemeColorPreviewCard(
    seed: Int,
    style: ThemePaletteStyle,
    spec: ThemeColorSpec,
    title: String,
    summary: String,
    dark: Boolean,
) {
    val controller = remember(seed, style, spec, dark) {
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = Color(seed),
            colorSpec = spec,
            paletteStyle = style,
        )
    }

    MiuixTheme(controller = controller) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeColorSwatch(MiuixTheme.colorScheme.primary)
                    ThemeColorSwatch(MiuixTheme.colorScheme.secondaryContainer)
                    ThemeColorSwatch(MiuixTheme.colorScheme.tertiaryContainer)
                    ThemeColorSwatch(MiuixTheme.colorScheme.errorContainer)
                    ThemeColorSwatch(MiuixTheme.colorScheme.surfaceContainerHigh)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(text = "主要按钮", style = MiuixTheme.textStyles.button)
                    }
                    TextButton(text = "次要按钮", onClick = {}, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ThemeColorSwatch(color: Color) {
    Box(
        Modifier.size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color),
    )
}

/** 方案的三颗色点（预留在配色方案弹窗里用） */
@Composable
@Suppress("unused")
private fun ThemeColorStyleDots(seed: Int, style: ThemePaletteStyle, spec: ThemeColorSpec, dark: Boolean) {
    val controller = remember(seed, style, spec, dark) {
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = Color(seed),
            colorSpec = spec,
            paletteStyle = style,
        )
    }
    MiuixTheme(controller = controller) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StyleDotGap),
        ) {
            Box(Modifier.size(StyleDotSize).clip(CircleShape).background(MiuixTheme.colorScheme.primary))
            Box(Modifier.size(StyleDotSize).clip(CircleShape).background(MiuixTheme.colorScheme.secondary))
            Box(Modifier.size(StyleDotSize).clip(CircleShape).background(MiuixTheme.colorScheme.tertiaryContainer))
        }
    }
}
