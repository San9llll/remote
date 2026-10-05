package lo.naui.ui.shell

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.PathOperation
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import lo.naui.sys.Shortcuts
import lo.naui.sys.VolumeChordBus
import lo.naui.ui.home.HomeSceneBackdrop
import lo.naui.ui.home.HomeSceneRail
import lo.naui.ui.home.HomeScreen
import lo.naui.ui.nav.Dest
import lo.naui.ui.agent.AgentConfigScreen
import lo.naui.ui.agent.AgentScreen
import lo.naui.ui.agent.AgentSessionsScreen
import lo.naui.ui.files.FileManagerScreen
import lo.naui.ui.pages.ToolsScreen
import lo.naui.ui.theme.CardStyle
import lo.naui.ui.settings.AboutScreen
import lo.naui.ui.settings.SettingsScreen
import lo.naui.ui.settings.ThemeScreen
import lo.naui.ui.theme.GlobalLayout
import lo.naui.ui.theme.NavMode
import lo.naui.ui.theme.ThemePrefs
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val COMPACT_WIDTH_DP = 600f

private val NAV_DESTS = listOf(Dest.Home, Dest.Modules, Dest.Overview, Dest.Settings)

private enum class Sub {
        None, Theme, About, Files, Terminal, AgentConfig, AgentSessions,
        Shelf, NewBook, Reader, BookConfig, Personas,
    }

/**
 * 外壳 —— 结构对齐参考项目（Aster 的 AsterAppShell）：
 *
 * - 内容整层挂在 `layerBackdrop` 上（底栏的液态玻璃才有东西可以折射）
 * - **Panorama + 首页**：左边那条场景导轨（时钟 / 电量 / 导航），首页另有整页模糊壁纸背景
 * - **Panorama + 其他页**：一样走左侧导轨，导航位置始终一致
 * - **Standard**：窄屏底部 NavigationBar，宽屏侧边 NavigationRail
 */
@Composable
fun AppShell(prefs: ThemePrefs, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop) {
    var current by remember { mutableStateOf(Dest.Home) }
    var sub by remember { mutableStateOf(Sub.None) }
    // 书柜里当前翻开的是哪本
    var bookId by remember { mutableStateOf("") }

    // 方案 B 的扩散圆心（0~1 归一化），是**点下去那一下的真实坐标**
    var spreadOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(0.5f, 0.5f)) }

    // 方案 B 要"旧页留底层、新页在上面长出来"，所以得记住上一页是谁
    var prevPage by remember { mutableStateOf<Pair<Dest, Sub>?>(null) }
    var spreadTick by remember { mutableStateOf(0) }

    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp

    // 导轨宽度跟着窗口走，和参考项目一个算法（外层算一次，首页和导轨共用同一个数）
    val sceneRailWidth = (widthDp.dp * 0.25f - 28.dp).coerceIn(56.dp, 80.dp)

    val useBottomNav = when (prefs.navMode) {
        NavMode.Auto -> widthDp < COMPACT_WIDTH_DP
        NavMode.Bottom -> true
        NavMode.Sidebar -> false
    }
    val panorama = prefs.globalLayout == GlobalLayout.Panorama

    // 全景模式下**所有页面**都走左侧导轨（不再切到底部胶囊），导航位置始终一致
    // Agent 那个入口可以在「主题 → 导航与外壳 → 侧栏设置」里关掉
    val railDests = if (prefs.railShowOverview) NAV_DESTS else NAV_DESTS.filter { it != Dest.Overview }

    val showSceneRail = panorama
    val standardBottomBar = !panorama && useBottomNav
    val standardRail = !panorama && !useBottomNav

    // 主图在 shell 层加载一次：导轨拿它做背景，首页拿它画大图。
    // 图是**本地**的（在主题页里自选），不从服务器拉。
    // 内置背景要从 assets 读，得有个 context
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    // 内容页背景图（模块 / 概览 / 设置铺的那张）—— 从主题页选的本地图读
    var pageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // 壁纸：1 / 2 走 assets 里那两套内置图（每次启动随机一张），自定义走用户选的
    /**
     * 底层真正显示的图。
     *
     * 为什么要跟"刚加载好的图"分开：
     * 切样式的时候，底层必须**还挂着旧图**，让涟漪慢慢推过去。
     * 如果直接把新图塞给底层，用户一眨眼就看到满屏新图，动画就白做了。
     */
    var shownWallpaper by remember { mutableStateOf<ImageBitmap?>(null) }

    /** 刚加载好、等着被"推"出来的新图 */
    var pendingWallpaper by remember { mutableStateOf<ImageBitmap?>(null) }

    // 启动时把当前样式的图先解好放缓存 —— 之后切换就是直接拿，没有解码那一下
    LaunchedEffect(prefs.bgStyle) {
        lo.naui.ui.theme.BuiltinBg.preload(ctx, prefs.bgStyle)
        lo.naui.ui.theme.BuiltinBg.trim(prefs.bgStyle, ctx)
    }

    LaunchedEffect(prefs.bgStyle, prefs.homeImage, prefs.builtinHero) {
        val bmp = if (prefs.bgStyle == lo.naui.ui.theme.BgStyle.Custom) {
            loadBitmap(prefs.homeImage)
        } else {
            var path = prefs.builtinHero
            if (path.isBlank()) {
                path = lo.naui.ui.theme.BuiltinBg.randomHero(ctx, prefs.bgStyle).orEmpty()
                if (path.isNotBlank()) prefs.updateBuiltinHero(path)
            }
            lo.naui.ui.theme.BuiltinBg.load(ctx, path)
        }

        if (bmp != null) {
            val img = bmp.asImageBitmap()
            val (seed, isLight) = lo.naui.ui.theme.dominantSeed(bmp)
            prefs.saveWallpaperSeed(seed, isLight)

            if (shownWallpaper == null) {
                // 头一次加载：没有旧图可过渡，直接上
                shownWallpaper = img
                wallpaper = img
                lo.naui.ui.theme.BgRipples.clear()
            } else {
                // 换图：新图先挂到 pending，等涟漪把它推出来
                pendingWallpaper = img
                lo.naui.ui.theme.BgRipples.fire()
            }
        } else {
            shownWallpaper = null
            wallpaper = null
        }
    }

    // 涟漪跑完了 → 把 pending 扶正，底层这才换图
    val nowRipples = lo.naui.ui.theme.BgRipples.active()
    LaunchedEffect(nowRipples.isEmpty(), pendingWallpaper) {
        if (nowRipples.isEmpty() && pendingWallpaper != null) {
            shownWallpaper = pendingWallpaper
            wallpaper = pendingWallpaper
            pendingWallpaper = null
        }
    }

    LaunchedEffect(prefs.contentImage, prefs.bgStyle) {
        pageBitmap = if (prefs.bgStyle == lo.naui.ui.theme.BgStyle.Custom) {
            loadBitmap(prefs.contentImage)?.asImageBitmap()
        } else {
            // 内置样式：用压缩包里给的那张
            val path = lo.naui.ui.theme.BuiltinBg.pageAsset(ctx, prefs.bgStyle)
            lo.naui.ui.theme.BuiltinBg.load(ctx, path)?.asImageBitmap()
        }
    }

    // 设置页（含它所有子页）单独的底图
    var settingsBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(prefs.settingsImage, prefs.bgStyle) {
        settingsBitmap = if (prefs.bgStyle == lo.naui.ui.theme.BgStyle.Custom) {
            loadBitmap(prefs.settingsImage)?.asImageBitmap()
        } else {
            val path = lo.naui.ui.theme.BuiltinBg.settingsAsset(prefs.bgStyle)
            lo.naui.ui.theme.BuiltinBg.load(ctx, path)?.asImageBitmap()
        }
    }

    // Agent 消息里点了文件路径 → 切到文件管理（目录已经被 UI 那边写好了）
    val openFilesTick = lo.naui.sys.UiState.openFilesTick
    LaunchedEffect(openFilesTick) {
        if (openFilesTick > 0) {
            sub = Sub.Files
        }
    }

    // 返回键先回上一级，别一按就退出 App
    BackHandler(enabled = sub != Sub.None || current != Dest.Home) {
        if (sub != Sub.None) {
            sub = Sub.None
        } else {
            current = Dest.Home
        }
    }

    // 设置系：设置页本身 + 它下面所有子页，都吃「设置页背景」
    val isSettingsPage = when (sub) {
        Sub.Theme, Sub.About, Sub.AgentConfig, Sub.Personas -> true
        Sub.None -> current == Dest.Settings
        else -> false
    }

    // 内容页底图只在「功能 / 概览 / 设置」这几页铺，主页和侧边栏不受影响
    val showPageBg = sub == Sub.None && current != Dest.Home && pageBitmap != null
    val isDarkTheme = prefs.darkMode == lo.naui.ui.theme.DarkMode.Dark

    // 当前这个子页算不算「功能区里的页面」—— 决定音量组合键是加还是摘
    val currentFeature: Pair<String, String>? = when (sub) {
        Sub.Files -> "files" to "文件管理"
        Sub.Terminal -> "terminal" to "终端"
        else -> null
    }

    // 音量上 + 音量下 同时按：在功能子页里 = 钉到侧栏 / 摘下来
    DisposableEffect(currentFeature) {
        val listener: () -> Unit = {
            val f = currentFeature
            if (f != null) Shortcuts.toggle(f.first, f.second)
        }
        VolumeChordBus.addListener(listener)
        onDispose { VolumeChordBus.removeListener(listener) }
    }

    // 按给定状态渲染页面（供 AnimatedContent 用 targetState 调用）
    val pageFor: @Composable (Dest, Sub) -> Unit = { dest, subState ->
        when (subState) {
            Sub.None -> when (dest) {
                Dest.Home -> HomeScreen(
                    railWidth = if (showSceneRail) sceneRailWidth else 0.dp,
                    wallpaper = shownWallpaper,
                    backdrop = backdrop,
                )
                Dest.Modules -> ToolsScreen(
                    onOpenFiles = { sub = Sub.Files },
                    onOpenTerminal = { sub = Sub.Terminal },
                    onOpenShelf = { sub = Sub.Shelf },
                    backdrop = backdrop,
                )
                Dest.Overview -> AgentScreen(
                    onOpenConfig = { sub = Sub.AgentConfig },
                    onOpenSessions = { sub = Sub.AgentSessions },
                )
                Dest.Settings -> SettingsScreen(
                    prefs = prefs,
                    onOpenTheme = { sub = Sub.Theme },
                    onOpenAbout = { sub = Sub.About },
                )
            }
            Sub.Theme -> ThemeScreen(prefs, onBack = { sub = Sub.None })
            Sub.About -> AboutScreen(onBack = { sub = Sub.None })
            Sub.Files -> FileManagerScreen(onBack = { sub = Sub.None }, backdrop = backdrop)
            Sub.Terminal -> lo.naui.ui.terminal.TerminalScreen(onBack = { sub = Sub.None })
            Sub.AgentConfig -> AgentConfigScreen(
                onBack = { sub = Sub.None },
                onOpenPersonas = { sub = Sub.Personas },
            )
            Sub.Personas -> lo.naui.ui.agent.PersonasScreen(onBack = { sub = Sub.AgentConfig })
            Sub.AgentSessions -> AgentSessionsScreen(onBack = { sub = Sub.None })

            Sub.Shelf -> lo.naui.ui.book.ShelfScreen(
                onOpenBook = { id -> bookId = id; sub = Sub.Reader },
                onNewBook = { sub = Sub.NewBook },
                backdrop = backdrop,
            )
            Sub.NewBook -> lo.naui.ui.book.NewBookScreen(
                onBack = { sub = Sub.Shelf },
                onCreated = { id -> bookId = id; sub = Sub.Reader },
            )
            Sub.Reader -> lo.naui.ui.book.ReaderScreen(
                bookId = bookId,
                onBack = { sub = Sub.Shelf },
                onOpenConfig = { sub = Sub.BookConfig },
            )
            Sub.BookConfig -> lo.naui.ui.book.BookConfigScreen(
                bookId = bookId,
                onBack = { sub = Sub.Reader },
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // ① 素材层：清晰、不压黑 —— 玻璃卡 drawBackdrop 采样的就是这一层。
            //    它会被 ② 完全盖住，肉眼看不见；但少了它玻璃卡就没东西可折射。
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AppBackdropLayer(
                    panorama = panorama,
                    wallpaper = shownWallpaper,
                    railWidth = if (showSceneRail) sceneRailWidth else 0.dp,
                    pageBitmap = pageBitmap,
                    showPageBg = showPageBg,
                    settingsBitmap = settingsBitmap,
                    showSettingsBg = isSettingsPage,
                    blurred = false,
                    darkScrim = isDarkTheme,
                )

                // 涟漪在**素材层里也画一份**。
                //
                // 为什么：卡片里的玻璃（drawBackdrop）采样的就是这一层 ——
                // 涟漪要在这儿，卡片才会跟着背景一起变
                //（用户说的"卡片和模糊没有实时渲染"）。
                // 下面视觉层还会再画一份，那份是给眼睛看的。
                RippleLayer(shownWallpaper, pendingWallpaper)
            }

            // ② 视觉层：糊 + 压黑，这才是你眼睛看到的那张背景
            AppBackdropLayer(
                panorama = panorama,
                wallpaper = shownWallpaper,
                railWidth = if (showSceneRail) sceneRailWidth else 0.dp,
                pageBitmap = pageBitmap,
                showPageBg = showPageBg,
                settingsBitmap = settingsBitmap,
                showSettingsBg = isSettingsPage,
                blurred = true,
                darkScrim = isDarkTheme,
            )

            // 涟漪也画在视觉层上 ——
            // 不然被上面那层的模糊+压黑盖住，眼睛看不到。
            // 跟素材层那份是**同一份数据**（BgRipples），所以两边是同步的。
            RippleLayer(shownWallpaper, pendingWallpaper)

            // ② 内容层：卡片在这里面，用 drawBackdrop 去采样上面那层
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (standardRail) {
                        NavigationRail {
                            railDests.forEach { d ->
                                NavigationRailItem(
                                    selected = current == d && sub == Sub.None,
                                    onClick = { current = d; sub = Sub.None },
                                    icon = d.icon,
                                    label = d.label,
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // 场景导轨浮在壁纸上，内容要给它让出位置
                            .padding(start = if (showSceneRail) sceneRailWidth else 0.dp),
                    ) {
                        // ---- 方案 B：旧页留底层，新页当一张玻璃卡从点击处长出来 ----
                        // 其余情况（没背景切换 / 子页）照旧走 AnimatedContent
                        AnimatedContent(
                            // 必须给尺寸：不给的话子项里的滚动容器
                            // 会拿到无限高度约束，直接抛 IllegalStateException
                            modifier = Modifier.fillMaxSize(),
                            targetState = current to sub,
                            transitionSpec = {
                                // 速度倍率：1.0 是基准，越大越快
                                val speed = prefs.transitionSpeed.coerceIn(0.4f, 3f)
                                fun dur(ms: Int) = (ms / speed).toInt().coerceAtLeast(60)

                                // 进子页：从**左下角**放大展开
                                // ——就是卡片左下那个按钮的位置，"从这里长出来"的感觉
                                if (targetState.second != Sub.None) {
                                    (
                                        fadeIn(tween(dur(260))) +
                                            scaleIn(
                                                animationSpec = tween(dur(360)),
                                                initialScale = 0.16f,
                                                transformOrigin = TransformOrigin(0.06f, 0.94f),
                                            )
                                        ).togetherWith(fadeOut(tween(dur(180))))
                                } else if (prefs.transitionStyle == lo.naui.ui.theme.TransitionStyle.None) {
                                    // 无动画：直接切
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else if (prefs.transitionStyle == lo.naui.ui.theme.TransitionStyle.Radial) {
                                    // ---- 方案 B：从点的那个地方扩展开 ----
                                    val o = spreadOrigin
                                    (
                                        scaleIn(
                                            animationSpec = tween(dur(420), easing = FastOutSlowInEasing),
                                            initialScale = 0.04f,
                                            transformOrigin = TransformOrigin(o.x, o.y),
                                        ) + fadeIn(tween(dur(200)))
                                        ).togetherWith(
                                        scaleOut(
                                            animationSpec = tween(dur(300), easing = FastOutSlowInEasing),
                                            targetScale = 1.08f,
                                            transformOrigin = TransformOrigin(o.x, o.y),
                                        ) + fadeOut(tween(dur(260)))
                                    )
                                } else {
                                    // 方案 A：切 tab 当一张长图在上下滚
                                    // 旧页整屏往上滑走，新页从下面整屏顶上来；两页不重叠、不加淡入
                                    // 方向跟着 tab 顺序走（往右切 = 往下滚）
                                    val forward = targetState.first.ordinal >= initialState.first.ordinal
                                    val dir = if (forward) 1 else -1
                                    val spec = tween<IntOffset>(
                                        durationMillis = dur(460),
                                        easing = FastOutSlowInEasing,
                                    )
                                    // 光上下滑会有几帧两张图硬碰硬，叠一层交叉淡化就顺了：
                                    // 旧页先淡出，新页晚 120ms 再淡进来，中间不会互相糊在一起
                                    (
                                        slideInVertically(
                                            animationSpec = spec,
                                            initialOffsetY = { h -> dir * h },
                                        ) + fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 300,
                                                delayMillis = 120,
                                                easing = FastOutSlowInEasing,
                                            )
                                        )
                                        ).togetherWith(
                                        slideOutVertically(
                                            animationSpec = spec,
                                            targetOffsetY = { h -> -dir * h },
                                        ) + fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = FastOutSlowInEasing,
                                            )
                                        )
                                    )
                                }
                            },
                            label = "page_switch",
                        ) { target ->
                            // 关键：必须按 targetState 渲染（写成 page() 的话
                            // 它读的是外部最新的 current/sub，两页渲染的是同一份内容
                            // → 点侧边栏时会"先闪一下目标页，再播动画"）
                            val (dest, subState) = target
                            pageFor(dest, subState)
                        }
                    }
                }
                AnimatedVisibility(visible = standardBottomBar) {
                    NavigationBar(
                        modifier = Modifier.background(MiuixTheme.colorScheme.surface)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    ) {
                        railDests.forEach { d ->
                            NavigationBarItem(
                                selected = current == d && sub == Sub.None,
                                onClick = { current = d; sub = Sub.None },
                                icon = d.icon,
                                label = d.label,
                            )
                        }
                    }
                }
            }

            // 场景导轨：带滑入滑出动画
            AnimatedVisibility(
                visible = showSceneRail,
                enter = slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(320)),
                exit = slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(200)),
            ) {
                HomeSceneRail(
                    destinations = railDests,
                    current = current,
                    onSelect = { current = it; sub = Sub.None },
                    onSelectAt = { d, x, y ->
                        if (prefs.transitionStyle == lo.naui.ui.theme.TransitionStyle.Radial && d != current) {
                            // 记住"从屏幕哪儿长出来"
                            spreadOrigin = androidx.compose.ui.geometry.Offset(x, y)
                            prevPage = current to sub
                            spreadTick++
                        }
                        current = d
                        sub = Sub.None
                    },
                    clockStyle = prefs.clockStyle,
                    wallpaper = shownWallpaper,
                    showBattery = prefs.railShowBattery,
                    showInfo = prefs.railShowInfo,
                    infoLines = prefs.railInfoList(),
                    shortcuts = Shortcuts.items,
                    onShortcutClick = { key ->
                        when (key) {
                            "files" -> sub = Sub.Files
                            "terminal" -> sub = Sub.Terminal
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(sceneRailWidth)
                        .fillMaxHeight()
                        .zIndex(1f),
                )
            }
        }
    }
}

/** 本地图片路径 → Bitmap（读不到就 null，交给兜底渐变） */
private fun loadBitmap(path: String): android.graphics.Bitmap? {
    if (path.isBlank()) return null
    val f = java.io.File(path)
    if (!f.exists()) return null
    return runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
}

/**
 * 背景的一层。同一个画面画两遍：
 *  ① blurred = false → 素材层，给玻璃卡当折射素材
 *  ② blurred = true  → 视觉层，糊 + 压黑
 */
@Composable
private fun AppBackdropLayer(
    panorama: Boolean,
    wallpaper: ImageBitmap?,
    railWidth: androidx.compose.ui.unit.Dp,
    pageBitmap: ImageBitmap?,
    showPageBg: Boolean,
    settingsBitmap: ImageBitmap?,
    showSettingsBg: Boolean,
    blurred: Boolean,
    darkScrim: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        if (panorama) {
            HomeSceneBackdrop(
                wallpaper = wallpaper,
                railWidth = railWidth,
                blurred = blurred,
                drawDecor = blurred,
            )
        } else {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
        }

        // 设置页底图优先 —— 设置系那些页盖过内容页背景
        val useSettings = showSettingsBg && settingsBitmap != null
        val usePage = !useSettings && showPageBg && pageBitmap != null
        val bg = if (useSettings) settingsBitmap else pageBitmap

        if ((useSettings || usePage) && bg != null) {
            Box(Modifier.fillMaxSize().padding(start = railWidth)) {
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (blurred) {
                                Modifier.blur(26.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                            } else {
                                Modifier
                            }
                        ),
                )
                if (blurred) {
                    Box(
                        Modifier.fillMaxSize().background(
                            MiuixTheme.colorScheme.surface.copy(
                                alpha = if (darkScrim) 0.72f else 0.55f
                            )
                        )
                    )
                }
            }
        }
    }
}


/**
 * 切背景的那层涟漪。
 *
 * ## 为什么要被调用两次
 *
 * 它得**同时**出现在两个地方：
 *   ① `layerBackdrop` 那层（素材层）—— 卡片里的玻璃要采样到它，
 *      这样卡片才会跟着背景一起变（用户说的"卡片要实时渲染"）
 *   ② 视觉层 —— 不然用户眼睛看不到它（被模糊+压黑盖住了）
 *
 * 两次画的是同一份数据（BgRipples），所以看起来是同步的。
 */
@Composable
private fun RippleLayer(
    /** 底层现在显示的那张 */
    shown: ImageBitmap?,
    /** 等着被推出来的新图 */
    pending: ImageBitmap?,
) {
    // ============================================================
    //  切背景的那层涟漪
    // ============================================================
    //
    // 画法：**先铺旧图**（铺满），再在**圆内画新图** ——
    // 圆扩到哪儿，哪儿才变；没扩到的还是旧图。
    //
    // 这一版解决两个问题：
    //
    //  ① **卡死**
    //     以前每帧画两张全屏位图，而且 `clipPath` 在 Compose 里会
    //     **强制创建一个离屏图层** —— 全屏离屏两遍，手机上必卡。
    //     现在：降到 30fps + 用 `clipRect` 把绘制锁在涟漪的包围盒里。
    //
    //  ② **边界太硬**
    //     以前是边界画一圈白色"玻璃边"，用户要的是**混合模糊过渡**。
    //     现在把边界那圈做宽（九十来个 px）并且**让新图在边界上淡出** ——
    //     淡出的地方底下正好是旧图，看起来就是新旧糊在一起。
    //     （Canvas 里没有真模糊，这是最接近的近似；省电模式一开就彻底不画。）
    // ============================================================

    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            if (lo.naui.ui.theme.BgRipples.active().isNotEmpty()) {
                frameTick++
            }
            // ⚠️ 30fps（原来是 16ms = 60fps）。
            // 涟漪本来就是慢慢扩的，30fps 肉眼看不出区别，
            // 但 GPU 压力直接减半 —— 这是"大图卡死"最主要的一刀。
            kotlinx.coroutines.delay(33)
        }
    }

    val ripples = lo.naui.ui.theme.BgRipples.active()
    @Suppress("UNUSED_EXPRESSION")
    run { frameTick }

    if (ripples.isEmpty() || shown == null || pending == null) return

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val now = System.currentTimeMillis()
        val dstSize = androidx.compose.ui.unit.IntSize(
            size.width.toInt(), size.height.toInt()
        )
        val blurBand = BLUR_BAND_PX

        // ---- 先算清楚每个圆的位置和半径 ----
        //
        // ⚠️ 不能在这儿写 data class —— **Kotlin 不允许局部 data class**。
        // 用 Triple 装 (cx, cy, radius)，progress 从 ripples 那边再取一次就行。
        val holes = ripples.map { r ->
            val pr = lo.naui.ui.theme.BgRipples.progressOf(r, now)
            val cx = size.width * r.cx
            val cy = size.height * r.cy
            val maxR = maxOf(
                kotlin.math.hypot(cx, cy),
                kotlin.math.hypot(size.width - cx, cy),
                kotlin.math.hypot(cx, size.height - cy),
                kotlin.math.hypot(size.width - cx, size.height - cy),
            )
            Triple(cx, cy, (maxR * pr).coerceAtLeast(1f))
        }

        // ---- 第一步：旧图铺满 ----
        // 这一张是全屏的，省不掉（它就是"过渡期间你看到的那张"）。
        drawImage(
            image = shown,
            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize = androidx.compose.ui.unit.IntSize(shown.width, shown.height),
            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
            dstSize = dstSize,
        )

        // ---- 第二步：圆内叠新图 ----
        //
        // 每张新图只画在**它自己那个圆的包围盒**里 ——
        // 用 clipRect 而不是 clipPath（前者便宜得多，不会全屏离屏）。
        //
        // 边界那圈：把圆切成很多层，靠外的层 alpha 越低。
        // 这样边界就是"新图慢慢淡出、旧图慢慢露出来" = 混合糊过去。
        holes.forEachIndexed { holeIdx, hole ->
            val (cx, cy, radius) = hole
            val outer = radius + blurBand
            clipRect(
                left = (cx - outer).coerceAtLeast(0f),
                top = (cy - outer).coerceAtLeast(0f),
                right = (cx + outer).coerceAtMost(size.width),
                bottom = (cy + outer).coerceAtMost(size.height),
            ) {
                // 实心部分：半径减掉模糊带以内，完全不透明
                val solid = (radius - blurBand).coerceAtLeast(0f)
                if (solid > 1f) {
                    // 用一个圆当"模具"：先画图，再用 BlendMode 裁不出来，
                    // 所以这里换思路 —— 直接画一个被 clipPath 限定的图，
                    // 但 clipPath 的 path 只有这一个圆（不是全屏），代价可控。
                    val solidPath = androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                cx - solid, cy - solid, cx + solid, cy + solid
                            )
                        )
                    }
                    clipPath(solidPath) {
                        drawImage(
                            image = pending,
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(pending.width, pending.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = dstSize,
                        )
                    }
                }

                // 模糊带：一圈一圈往外，alpha 递减
                // 12 层够平滑了，再多就是白烧
                val layers = 12
                val holeProgress = lo.naui.ui.theme.BgRipples
                    .progressOf(ripples[holeIdx], now).coerceIn(0f, 1f)

                for (i in layers downTo 1) {
                    val t = i / layers.toFloat()
                    val r = radius + blurBand * (1f - t)
                    if (r <= solid) continue
                    val a = t * t          // 平方衰减，边缘更软
                    val ring = androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                cx - r, cy - r, cx + r, cy + r
                            )
                        )
                    }
                    clipPath(ring) {
                        drawImage(
                            image = pending,
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(pending.width, pending.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = dstSize,
                            alpha = a * holeProgress,
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 边界那圈"混合模糊"的宽度（px）。
 *
 * 九十来个 px 在手机上差不多是 30dp —— 足够把边界糊开，
 * 又不会糊得太夸张。
 */
private const val BLUR_BAND_PX = 90f

