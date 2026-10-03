package lo.naui.ui.shell

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
    // 上一张壁纸 —— 切背景的时候要留着它当"被覆盖的那层"
    var prevWallpaper by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(prefs.bgStyle, prefs.homeImage, prefs.builtinHero) {
        // 换之前先把当前这张记下来
        if (wallpaper != null) prevWallpaper = wallpaper

        val bmp = if (prefs.bgStyle == lo.naui.ui.theme.BgStyle.Custom) {
            loadBitmap(prefs.homeImage)
        } else {
            // 内置那套：没挑过就先随机一张记下来
            var path = prefs.builtinHero
            if (path.isBlank()) {
                path = lo.naui.ui.theme.BuiltinBg.randomHero(ctx, prefs.bgStyle).orEmpty()
                if (path.isNotBlank()) prefs.updateBuiltinHero(path)
            }
            lo.naui.ui.theme.BuiltinBg.load(ctx, path)
        }

        if (bmp != null) {
            wallpaper = bmp.asImageBitmap()
            // 取色要的是 android.graphics.Bitmap，所以原图先留着
            val (seed, isLight) = lo.naui.ui.theme.dominantSeed(bmp)
            prefs.saveWallpaperSeed(seed, isLight)
        } else {
            wallpaper = null
        }
    }

    LaunchedEffect(prefs.contentImage) {
        pageBitmap = loadBitmap(prefs.contentImage)?.asImageBitmap()
    }

    // 设置页（含它所有子页）单独的底图
    var settingsBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(prefs.settingsImage) {
        settingsBitmap = loadBitmap(prefs.settingsImage)?.asImageBitmap()
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
                    wallpaper = wallpaper,
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
                    wallpaper = wallpaper,
                    railWidth = if (showSceneRail) sceneRailWidth else 0.dp,
                    pageBitmap = pageBitmap,
                    showPageBg = showPageBg,
                    settingsBitmap = settingsBitmap,
                    showSettingsBg = isSettingsPage,
                    blurred = false,
                    darkScrim = isDarkTheme,
                )
            }

            // ② 视觉层：糊 + 压黑，这才是你眼睛看到的那张背景
            AppBackdropLayer(
                panorama = panorama,
                wallpaper = wallpaper,
                railWidth = if (showSceneRail) sceneRailWidth else 0.dp,
                pageBitmap = pageBitmap,
                showPageBg = showPageBg,
                settingsBitmap = settingsBitmap,
                showSettingsBg = isSettingsPage,
                blurred = true,
                darkScrim = isDarkTheme,
            )

            // ①.5 涟漪遮罩：切背景的时候，旧图只在"圆外"露出来
            //
            // 为什么这么绕：要的是"新图从点击点长出来"。
            // 所以底下的背景层照旧画新的，上面这层把**旧图**盖住，
            // 但裁掉扩散圆覆盖的那部分 —— 圆扩到哪儿，哪儿就露出新图。
            // 涟漪要一帧一帧地推进，所以得有个东西持续触发重组。
            // 没有它的话 Canvas 只会画一次，圆就停在原地了。
            var frameTick by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    if (lo.naui.ui.theme.BgRipples.active().isNotEmpty()) {
                        frameTick++
                    }
                    kotlinx.coroutines.delay(16)   // ~60fps，只在有涟漪时才真正干活
                }
            }

            val ripples = lo.naui.ui.theme.BgRipples.active()
            @Suppress("UNUSED_EXPRESSION")
            run { frameTick }   // 让它参与重组

            if (ripples.isNotEmpty() && prevWallpaper != null) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().zIndex(0.2f)) {
                    val now = System.currentTimeMillis()

                    // 把所有扩散圆并成一条路径
                    val circles = androidx.compose.ui.graphics.Path()
                    ripples.forEach { r ->
                        val p = lo.naui.ui.theme.BgRipples.progressOf(r, now)
                        val cx = size.width * r.cx
                        val cy = size.height * r.cy
                        val maxR = maxOf(
                            kotlin.math.hypot(cx, cy),
                            kotlin.math.hypot(size.width - cx, cy),
                            kotlin.math.hypot(cx, size.height - cy),
                            kotlin.math.hypot(size.width - cx, size.height - cy),
                        )
                        val radius = (maxR * p).coerceAtLeast(1f)
                        circles.addOval(
                            androidx.compose.ui.geometry.Rect(
                                cx - radius, cy - radius, cx + radius, cy + radius
                            )
                        )
                    }

                    // 整屏 减 圆 = 圆外那块
                    val outside = androidx.compose.ui.graphics.Path().apply {
                        addRect(
                            androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
                        )
                        op(circles, androidx.compose.ui.graphics.PathOperation.Difference)
                    }

                    // 圆外画旧图（ImageBitmap 直接就能 drawImage，不用绕）
                    val old = prevWallpaper ?: return@Canvas
                    clipPath(outside) {
                        drawImage(
                            image = old,
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(old.width, old.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(
                                size.width.toInt(), size.height.toInt()
                            ),
                        )
                    }

                    // 切线：一圈液态玻璃的光，扩完慢慢淡掉
                    ripples.forEach { r ->
                        val p = lo.naui.ui.theme.BgRipples.progressOf(r, now)
                        val a = lo.naui.ui.theme.BgRipples.edgeAlphaOf(r, now)
                        if (p >= 1f || a <= 0.01f) return@forEach
                        val cx = size.width * r.cx
                        val cy = size.height * r.cy
                        val maxR = maxOf(
                            kotlin.math.hypot(cx, cy),
                            kotlin.math.hypot(size.width - cx, cy),
                            kotlin.math.hypot(cx, size.height - cy),
                            kotlin.math.hypot(size.width - cx, size.height - cy),
                        )
                        val radius = (maxR * p).coerceAtLeast(1f)
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.30f * a),
                                    androidx.compose.ui.graphics.Color.Transparent,
                                ),
                                center = androidx.compose.ui.geometry.Offset(cx, cy),
                                radius = radius,
                            ),
                            radius = radius,
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 22.dp.toPx()),
                        )
                    }
                }
            }

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
                    wallpaper = wallpaper,
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
