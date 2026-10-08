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

    // 背景模糊半径（主题页新加的滑块）。壁纸、内容页底图、涟漪三处共用一个数，
    // 这样切背景的时候圆里圆外才是同一种糊法。
    val bgBlurDp = prefs.bgBlur.dp

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

    // ⚠️ 原来这儿有个 pendingWallpaper（"刚加载好、等着被推出来的新图"）。
    // 1.00.0 删掉了：新图现在由**涟漪队列自己带着**（BgRipples.Ripple.to），
    // 一条涟漪一张图，连点几次就排几条，谁也不会顶掉谁 ——
    // 放在外壳里当单个变量的话，后一次必然把前一次覆盖掉。

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

            if (shownWallpaper == null && !lo.naui.ui.theme.BgRipples.running) {
                // 头一次加载：没有旧图可过渡，直接上
                shownWallpaper = img
                wallpaper = img
                lo.naui.ui.theme.BgRipples.clear()
            } else {
                // 换图：排进涟漪队列，由涟漪把它推出来。
                // 底层这张**先不动** —— 动了就等于跳过动画直接换图。
                lo.naui.ui.theme.BgRipples.fire(img)
            }
        } else {
            shownWallpaper = null
            wallpaper = null
        }
    }

    // 涟漪队列跑空了 → 把最新那张扶正，底层这才换图。
    //
    // ⚠️ 读 `running` 就是读 `list`，所以队列一变（入队 / 被 prune 摘空）这里都会
    // 重组 —— 这正是原来缺的那一环：没人通知外壳"涟漪结束了"。
    val ripplesRunning = lo.naui.ui.theme.BgRipples.running
    LaunchedEffect(ripplesRunning) {
        if (ripplesRunning) return@LaunchedEffect
        val next = lo.naui.ui.theme.BgRipples.settledTo ?: return@LaunchedEffect
        shownWallpaper = next
        wallpaper = next
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
            // ① 背景 —— 1.00.0 起**只画一遍**，拆成"图层"和"纱层"两块：
            //
            //   图层（挂在 layerBackdrop 里）：兜底渐变 → 壁纸（模糊可调）→ 涟漪 → 内容页底图
            //   纱层（在 layerBackdrop 外）：  压黑纱 → 导轨渐变 → 底图那层面板色
            //
            // 为什么拆：卡片折射的必须是**没压黑**的图，不然折出来一团黑
            //（老注释里踩过这个坑，当时的解法是再画一遍清晰素材层 —— 全屏壁纸每帧两遍）。
            // 现在把纱挪出去就不用画两遍了，观感跟 iOS 26 / Aster 那套一致：
            // 背景先糊，卡片再在上面折射，模糊强度还跟着主题页那个滑块走。
            val railW = if (showSceneRail) sceneRailWidth else 0.dp

            // 内容页 / 设置页铺的那张底图（设置页优先）。
            // ⚠️ 只在这儿算一次 —— 图层和纱层两边都要用同一个判断，
            // 各算各的迟早对不上（纱压错地方就是"背景突然变暗一块"）。
            val pageBg: ImageBitmap? = when {
                isSettingsPage && settingsBitmap != null -> settingsBitmap
                showPageBg && pageBitmap != null -> pageBitmap
                else -> null
            }

            // ── 采样层：卡片 drawBackdrop 折射的就是这一层 ──
            //
            // 里面**只有图，没有纱**。压黑纱挪到外面去了，理由写在
            // HomeSceneDecor 的注释里：纱被折进卡片就是一片黑。
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AppBackdropImages(
                    panorama = panorama,
                    wallpaper = shownWallpaper,
                    railWidth = railW,
                    pageBg = pageBg,
                    bgBlur = bgBlurDp,
                )

                // 涟漪画在采样层**内部**、用同一个模糊半径 ——
                // 圆里圆外一种糊法，过渡才不割裂，卡片也跟着一起变。
                //（用户报的"图片未进行模糊和玻璃渲染就直接投影"，就是因为它
                //  原来画在模糊层之上、画的还是清晰原图。）
                RippleLayer(shown = shownWallpaper, bgBlur = bgBlurDp)
            }

            // ── 纱层：压黑 + 导轨渐变 + 内容页底图那层面板色 ──
            AppBackdropScrim(
                panorama = panorama,
                railWidth = railW,
                darkScrim = isDarkTheme,
                hasPageBg = pageBg != null,
            )

            // ② 内容层：卡片在这里面，用 drawBackdrop 采样上面那层背景。
            //
            // ⚠️ 这里原来包着一段"涟漪期间把 backdrop 置成 null"的 hack，**已删**。
            //
            // 它的理由是「layerBackdrop 里写着 shouldAutoInvalidate = false，
            // 不会每帧重录」。但那是旧版本的源码 —— 本项目用的 backdrop 是 **2.0.1**，
            // 我把 LayerBackdropModifier.kt 拉下来核对过：那个字段**根本不存在**，
            // 节点走默认值，draw() 里就是 drawContent() + recordLayer(...)，
            // 子树一重绘它就重录。所以这个 hack 不但没必要，还带来一个真 bug：
            //
            //   ripplesActive 是**组合期算的一次性值**，涟漪跑完之后没有任何状态变化
            //   触发重组 → 它永远停在 true → LocalGlassBackdrop 永远是 null
            //   → 卡片玻璃退化成半透明叠色，直到用户切个页面才恢复。
            //
            // 现在 backdrop 由 MainActivity 那层一直提供着，涟漪期间卡片照常折射；
            // 而且涟漪就画在被采样的那一层里，所以卡片是**跟着背景一起变**的。
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
 * 背景里**纯图**的那部分（挂在 layerBackdrop 里，卡片会折射它）。
 *
 * 1.00.0 之前这里叫 AppBackdropLayer，同一个画面画两遍（清晰素材层 + 糊的视觉层）。
 * 现在只画一遍，而且**不含任何压黑纱** —— 纱在 [AppBackdropScrim] 里，
 * 画在采样层外面，免得卡片折射出来一团黑。
 */
@Composable
private fun AppBackdropImages(
    panorama: Boolean,
    wallpaper: ImageBitmap?,
    railWidth: androidx.compose.ui.unit.Dp,
    /** 内容页 / 设置页那张底图，null = 这页不铺 */
    pageBg: ImageBitmap?,
    bgBlur: androidx.compose.ui.unit.Dp,
) {
    Box(Modifier.fillMaxSize()) {
        if (panorama) {
            HomeSceneBackdrop(wallpaper = wallpaper, bgBlur = bgBlur)
        } else {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
        }

        if (pageBg != null) {
            Box(Modifier.fillMaxSize().padding(start = railWidth)) {
                Image(
                    bitmap = pageBg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // 底图原来写死 26dp，壁纸写死 32dp（比例 ≈ 0.8）。
                            // 现在都吃同一个滑块，比例保持不变。
                            if (bgBlur * 0.8f > 0.dp) {
                                Modifier.blur(
                                    bgBlur * 0.8f,
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle,
                                )
                            } else {
                                Modifier
                            }
                        ),
                )
            }
        }
    }
}

/**
 * 背景上面那几层纱。**画在 layerBackdrop 外面** —— 纱是给眼睛看的，
 * 折进卡片就是一片黑（老注释里踩过）。
 */
@Composable
private fun AppBackdropScrim(
    panorama: Boolean,
    railWidth: androidx.compose.ui.unit.Dp,
    darkScrim: Boolean,
    hasPageBg: Boolean,
) {
    // 壁纸那层压黑 + 导轨渐变
    if (panorama) {
        HomeSceneDecor(railWidth = railWidth, fullScrim = !hasPageBg)
    }
    // 内容页底图上面那层面板色（原来跟在底图后面画，现在挪到这儿，效果一样）
    if (hasPageBg) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = railWidth)
                .background(
                    MiuixTheme.colorScheme.surface.copy(alpha = if (darkScrim) 0.72f else 0.55f)
                )
        )
    }
}


/**
 * 切背景的那层涟漪。
 *
 * ## 1.00.0 改了四件事
 *
 * **① 只画一遍。** 原来素材层、视觉层各画一份（"两边是同步的"），
 * 现在它就在被卡片采样的那一层里，卡片自然跟着变，不用再复制一份给眼睛。
 *
 * **② 队列。** 每条涟漪自带它要推出来的那张图（`BgRipples.Ripple.to`），
 * 按时间顺序叠着画 —— 连点三次就三条，每条都会跑完自己那 800ms。
 * 原来只有一个 pending 槽位 + 外壳里一对 shown/pending，后一次直接顶掉前一次，
 * 用户看到的就是"只有最后一次生效"。
 *
 * **③ 跟着背景一起糊。** 整个 Canvas 挂上跟壁纸**同一个**模糊半径。
 * 原来它画在模糊层之上、画的是清晰原图，于是过渡那 800ms 里圆内 sharp、
 * 圆外 blur，结束瞬间整屏跳回糊的 —— 就是用户报的"图片未进行模糊就直接投影"。
 * 顺带：有了真模糊，原来那 12 层"假模糊带"砍到 3 层就够了，省一大截绘制。
 * 底层那张旧图也不用再铺了（下面那层已经画过同一张，重复铺纯属白烧）。
 *
 * **④ 帧驱动只在跑的时候存在。** 原来是 `LaunchedEffect(Unit) { while(true) { delay(33) } }`
 * 常驻空转，而且画两遍 = **两个**这样的协程。现在队列一空就退出，
 * 空闲时一个协程都不留；帧号在**绘制阶段**读，所以每帧只让绘制失效，不触发重组。
 */
@Composable
private fun RippleLayer(
    /** 底层现在显示的那张（涟漪从它上面长出来） */
    shown: ImageBitmap?,
    /** 跟壁纸同一个模糊半径 —— 圆里圆外必须一种糊法 */
    bgBlur: androidx.compose.ui.unit.Dp,
) {
    // 组合期只读这一个：决定要不要挂帧循环（队列一变就重组，一次过渡也就两三次）
    val running = lo.naui.ui.theme.BgRipples.running
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var f = 0
        while (true) {
            // 跟渲染对齐（原来用 delay(33)，那个跟帧不同步，会撕裂也会白跑）
            androidx.compose.runtime.withFrameNanos { }

            // ⚠️ 但**只每两帧推一次**，等于 30fps。
            // 前人把涟漪从 60 降到 30 是拿实机卡顿换来的（坑 #45），这刀得留着：
            // 现在涟漪每帧还带着壁纸模糊 + backdrop 重录 + 卡片重采样一起跑，
            // 帧率翻倍就是全套开销翻倍。涟漪本来就是慢慢扩的，30fps 肉眼看不出。
            if (f++ % 2 == 0) frameTick++

            // 把跑完的摘掉 —— 摘空的那一刻队列变化会通知外壳"该扶正背景了"
            lo.naui.ui.theme.BgRipples.prune(System.currentTimeMillis())
            if (!lo.naui.ui.theme.BgRipples.running) break
        }
    }

    if (!running || shown == null) return

    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxSize()
            .then(
                if (bgBlur > 0.dp) {
                    Modifier.blur(bgBlur, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                } else {
                    Modifier
                }
            )
    ) {
        // 在**绘制阶段**读帧号：每帧只让这一个节点重绘，不触发重组
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val now = System.currentTimeMillis()
        val ripples = lo.naui.ui.theme.BgRipples.active(now)
        if (ripples.isEmpty()) return@Canvas

        val dstSize = androidx.compose.ui.unit.IntSize(
            size.width.toInt(), size.height.toInt()
        )
        val blurBand = BLUR_BAND_PX

        // 一条一条按时间顺序叠：后一条的圆扩过来才盖住前一条，
        // 所以每一次切换都看得到它自己那趟扩散跑完。
        ripples.forEach { r ->
            val pr = lo.naui.ui.theme.BgRipples.progressOf(r, now)
            val img = r.to
            val cx = size.width * r.cx
            val cy = size.height * r.cy
            val maxR = maxOf(
                kotlin.math.hypot(cx, cy),
                kotlin.math.hypot(size.width - cx, cy),
                kotlin.math.hypot(cx, size.height - cy),
                kotlin.math.hypot(size.width - cx, size.height - cy),
            )
            val radius = (maxR * pr).coerceAtLeast(1f)
            val outer = radius + blurBand

            // ---- 按 Crop 算源矩形 ----
            //
            // ⚠️ 原来这里是 srcSize = 整张图、dstSize = 整个屏幕，等于 FillBounds
            //（直接拉满），而背景那张壁纸走的是 ContentScale.Crop。
            // 同一张图两种裁法 → 圆的边上看得见错位，看着就像"新图直接投影上去的"
            //（用户报的正是这个）。现在按 Crop 的算法取中间那块，跟背景对齐。
            val scale = maxOf(size.width / img.width, size.height / img.height)
            val srcW = (size.width / scale).toInt().coerceIn(1, img.width)
            val srcH = (size.height / scale).toInt().coerceIn(1, img.height)
            val srcOff = androidx.compose.ui.unit.IntOffset(
                (img.width - srcW) / 2, (img.height - srcH) / 2
            )
            val srcSz = androidx.compose.ui.unit.IntSize(srcW, srcH)

            // 用 clipRect 把绘制锁在这个圆的包围盒里 ——
            // clipPath 会强制全屏离屏，那个是"大图卡死"的老原因（坑 #45）
            clipRect(
                left = (cx - outer).coerceAtLeast(0f),
                top = (cy - outer).coerceAtLeast(0f),
                right = (cx + outer).coerceAtMost(size.width),
                bottom = (cy + outer).coerceAtMost(size.height),
            ) {
                // 实心部分：半径减掉模糊带以内，完全不透明
                val solid = (radius - blurBand).coerceAtLeast(0f)
                if (solid > 1f) {
                    val solidPath = androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                cx - solid, cy - solid, cx + solid, cy + solid
                            )
                        )
                    }
                    clipPath(solidPath) {
                        drawImage(
                            image = img,
                            srcOffset = srcOff,
                            srcSize = srcSz,
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = dstSize,
                        )
                    }
                }

                // 边界那圈淡出：3 层够了 —— 整层还挂着真模糊，
                // 原来那 12 层假模糊带是在"没有真模糊"的年代用来凑柔和边的。
                val layers = 3
                for (i in layers downTo 1) {
                    val t = i / layers.toFloat()
                    val rr = radius + blurBand * (1f - t)
                    if (rr <= solid) continue
                    val a = t * t * pr        // 平方衰减，边缘更软；跟着进度淡入
                    val ring = androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(cx - rr, cy - rr, cx + rr, cy + rr)
                        )
                    }
                    clipPath(ring) {
                        drawImage(
                            image = img,
                            srcOffset = srcOff,
                            srcSize = srcSz,
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = dstSize,
                            alpha = a,
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

