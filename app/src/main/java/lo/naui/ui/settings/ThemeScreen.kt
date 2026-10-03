package lo.naui.ui.settings

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import lo.naui.ui.theme.BgStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import lo.naui.ui.component.GlassCard
import lo.naui.ui.component.IndicatorSwitchPreference
import lo.naui.ui.theme.CardStyle
import lo.naui.ui.theme.TransitionStyle
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
    // 主页大图：可以一次挑**多张**，都复制进 App 私有目录（本地图，不走服务器）。
    // 启动时随机挑一张显示，主页上滑换下一张。
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        runCatching {
            val saved = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val target = java.io.File(ctx.filesDir, "hero_" + System.currentTimeMillis() + "_" + i + ".jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                saved += target.absolutePath
            }
            prefs.addHeroImages(saved)
        }
    }
    // 设置页背景（设置及其所有子页用）
    val settingsPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val target = java.io.File(ctx.filesDir, "settings_bg.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.updateSettingsImage(target.absolutePath)
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

    // 注意：这里原来用的是 Miuix 的 Scaffold —— 它会自己铺一层 containerColor，
    // 把外壳画在底层的那张「设置页背景」整个盖住，看着就像背景没生效。
    // 换成自己搭：标题 + LazyColumn，背景直接透出来。
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onBack() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("← 返回", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(6.dp))
                Text("主题", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
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
                SettingsCard(index = 0) {
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

            item(key = "motion") {
                SectionTitle("动态效果")
                SettingsCard(index = 1) {
                    ArrowPreference(
                        title = "切页动效",
                        summary = prefs.transitionStyle.summary,
                        startAction = { SettingsIcon(MiuixIcons.Refresh) },
                        onClick = { dialog = "transition" },
                    )
                    AnimatedVisibility(
                        visible = prefs.transitionStyle != TransitionStyle.None,
                        enter = expandVertically(tween(300)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(240)) + fadeOut(tween(160)),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            SliderPreference(
                                value = prefs.transitionSpeed,
                                onValueChange = { prefs.updateTransitionSpeed(it) },
                                title = "速度",
                                summary = "越大越快",
                                valueText = String.format("%.2f×", prefs.transitionSpeed),
                                valueRange = 0.4f..3f,
                                steps = 12,
                            )
                        }
                    }
                }
            }

            item(key = "card") {
                SectionTitle("卡片")
                SettingsCard(index = 2) {
                    ArrowPreference(
                        title = "卡片风格",
                        summary = prefs.cardStyle.summary,
                        startAction = { SettingsIcon(MiuixIcons.Layers) },
                        onClick = { dialog = "cardstyle" },
                    )

                    // 选了液态玻璃，下面这几个旋钮才展开
                    AnimatedVisibility(
                        visible = prefs.cardStyle == CardStyle.Liquid,
                        enter = expandVertically(tween(320)) + fadeIn(tween(240)),
                        exit = shrinkVertically(tween(260)) + fadeOut(tween(160)),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
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
                                summary = "边缘那道液态折射的强度",
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
                                value = prefs.dialogScrim,
                                onValueChange = { prefs.updateDialogScrim(it) },
                                title = "弹窗底层暗度",
                                summary = "弹窗后面那层遮罩有多暗（危险请求那个弹窗也算）",
                                valueText = String.format("%.2f", prefs.dialogScrim),
                                valueRange = 0.1f..0.9f,
                                steps = 15,
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
                }
            }

            item(key = "dark") {
                SectionTitle("明暗与密度")
                SettingsCard(index = 3) {
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
                SettingsCard(index = 4) {
                    ArrowPreference(
                        title = "首页布局",
                        summary = prefs.homeLayout.summary,
                        startAction = { SettingsIcon(MiuixIcons.Tune) },
                        onClick = { dialog = "home" },
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
                SettingsCard(index = 5) {
                    // ---- 样式三选一 ----
                    // 1 / 2 是打包在 assets 里的两套（用户自己挑的图），
                    // 选了就随机挑一张；自定义才用下面那些自己设的。
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Text(
                            "样式",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BgStyle.entries.forEach { st ->
                                val on = prefs.bgStyle == st
                                // 记一下这个按钮在屏幕上的位置，换背景时从这儿扩散
                                var cx by remember { mutableFloatStateOf(0.5f) }
                                var cy by remember { mutableFloatStateOf(0.5f) }
                                val screenW = androidx.compose.ui.platform.LocalConfiguration
                                    .current.screenWidthDp.coerceAtLeast(1)
                                val screenH = androidx.compose.ui.platform.LocalConfiguration
                                    .current.screenHeightDp.coerceAtLeast(1)

                                Box(
                                    Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInWindow()
                                            cx = (pos.x + coords.size.width / 2f) / screenW
                                            cy = (pos.y + coords.size.height / 2f) / screenH
                                        }
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (on) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .clickable {
                                            // 从按钮中心起一圈，再换风格 ——
                                            // 1 秒内连点会叠着来（BgRipples 存的是列表）
                                            lo.naui.ui.theme.BgRipples.add(cx, cy)
                                            prefs.updateBgStyle(st)
                                        }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        st.label,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                        color = if (on) MiuixTheme.colorScheme.onPrimary
                                        else MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when (prefs.bgStyle) {
                                BgStyle.Nk -> "样式 1 · 内置 " +
                                    lo.naui.ui.theme.BuiltinBg.heroAssets(ctx, BgStyle.Nk).size +
                                    " 张，每次启动随机挑，主页上滑换下一张"
                                BgStyle.Gc -> "样式 2 · 内置 " +
                                    lo.naui.ui.theme.BuiltinBg.heroAssets(ctx, BgStyle.Gc).size +
                                    " 张，权限弹窗也有专属背景"
                                BgStyle.Custom -> "自定义 · 用下面自己选的那些图"
                            },
                            fontSize = 10.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    // ---- 自定义才展开下面这些 ----
                    androidx.compose.animation.AnimatedVisibility(
                        visible = prefs.bgStyle == BgStyle.Custom,
                        enter = androidx.compose.animation.expandVertically(
                            androidx.compose.animation.core.tween(280)
                        ) + androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(220)
                        ),
                        exit = androidx.compose.animation.shrinkVertically(
                            androidx.compose.animation.core.tween(220)
                        ) + androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(140)
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = "主页大图",
                        summary = when {
                            prefs.heroImages.isEmpty() -> "还没选（用主题渐变兜底）· 可以一次挑多张"
                            prefs.heroImages.size == 1 -> "1 张 · 每次启动随机挑，点一下再加几张"
                            else -> "已选 " + prefs.heroImages.size + " 张 · 每次启动随机挑一张，主页上滑换下一张"
                        },
                        startAction = { SettingsIcon(MiuixIcons.Photos) },
                        onClick = { imagePicker.launch("image/*") },
                    )
                    if (prefs.heroImages.isNotEmpty()) {
                        ArrowPreference(
                            title = "换一张看看",
                            summary = "不用等下次启动，现在就换",
                            onClick = { prefs.nextHero(ctx) },
                        )
                        ArrowPreference(
                            title = "清除主页大图",
                            summary = "全部清掉，回到主题渐变兜底",
                            onClick = { prefs.clearHeroImages() },
                        )
                    }

                        }
                    }

                    ArrowPreference(
                        title = "设置页背景",
                        summary = if (prefs.settingsImage.isBlank()) {
                            "还没选 · 设置里的所有子页都用它"
                        } else {
                            "已设置 · 用于设置及其所有子页"
                        },
                        startAction = { SettingsIcon(MiuixIcons.Photos) },
                        onClick = { settingsPicker.launch("image/*") },
                    )
                    if (prefs.settingsImage.isNotBlank()) {
                        ArrowPreference(
                            title = "清除设置页背景",
                            summary = "回到主题底色",
                            onClick = { prefs.updateSettingsImage("") },
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
        "home" -> OptionDialog(
            show = true, title = "首页布局",
            options = HomeLayout.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.homeLayout.id,
            onPick = { id -> HomeLayout.entries.firstOrNull { it.id == id }?.let { prefs.updateHomeLayout(it) } },
            onDismiss = { dialog = null },
        )
        "clock" -> OptionDialog(
            show = true, title = "时钟样式",
            options = ClockStyle.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.clockStyle.id,
            onPick = { id -> ClockStyle.entries.firstOrNull { it.id == id }?.let { prefs.updateClockStyle(it) } },
            onDismiss = { dialog = null },
        )
        "transition" -> OptionDialog(
            show = true, title = "切页动效",
            options = TransitionStyle.entries.map { Option(it.id, it.label, it.summary) },
            currentId = prefs.transitionStyle.id,
            onPick = { id -> prefs.updateTransitionStyle(TransitionStyle.of(id)) },
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
private fun SettingsCard(index: Int = -1, content: @Composable () -> Unit) {
    // 和其它页保持一致：玻璃卡 + 入场动画
    GlassCard(
        backdrop = null,
        enterIndex = index,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
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
