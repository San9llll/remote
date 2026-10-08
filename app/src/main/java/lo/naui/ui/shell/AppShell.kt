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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import lo.naui.sys.Shortcuts
import lo.naui.sys.VolumeChordBus
import lo.naui.ui.home.HomeSceneBackdrop
import lo.naui.ui.home.HomeSceneDecor
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

    // ---- 涟漪的帧驱动：**整个外壳只有这一个循环** ----
    //
    // 原来这个循环长在 RippleLayer 里面，而 RippleLayer 被画两遍 = 两个常驻空转协程。
    // 现在提到这儿，而且：
    //   · 挂 ripplesRunning —— 队列一空就退出，空闲时**一个协程都不留**
    //   · withFrameNanos 跟渲染对齐（原来的 delay(33) 跟帧不同步）
    //   · 每两帧才推一次 = 30fps —— 坑 #45 那一刀是拿实机卡顿换来的，留着
    // 帧号是个 mutableIntStateOf，**只在绘制阶段读**，所以每帧只让那一个节点重绘，
    // 不会把整个外壳带着重组一遍。
    val rippleFrame = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(ripplesRunning) {
        if (!ripplesRunning) return@LaunchedEffect
        var f = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            if (f++ % 2 == 0) rippleFrame.intValue++
            lo.naui.ui.theme.BgRipples.prune(System.currentTimeMillis())
            if (!lo.naui.ui.theme.BgRipples.running) break
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

            // ── ① 采样层：**清晰**的壁纸 + 涟漪，一点不糊 ──
            //
            // 卡片 drawBackdrop 采的是这一层。lens 折的是细节，喂它糊图就白折 ——
            // 这就是上一版"液态玻璃没生效"的原因（用户：玻璃直接作用在背景上，
            // 不要作用在模糊上）。
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AppBackdropImages(
                    panorama = panorama,
                    wallpaper = shownWallpaper,
                    railWidth = railW,
                    pageBg = pageBg,
                )
                RippleLayer(shown = shownWallpaper, frame = rippleFrame)
            }

            // ── ② 眼睛看到的糊背景：把 ① 录下来的那一层**重放**一遍 + 一次模糊 ──
            //
            // 关键是"重放"不是"再画一遍"：① 已经把壁纸画进 GraphicsLayer 了，
            // 这儿只是把那层贴出来、顺带来一趟 RenderEffect 模糊。
            // 全屏位图自始至终只画一次，模糊也只走一次 —— 用户要的"每帧只多一次
            // 模糊、不重画位图"就是这个意思。
            //
            // blur 的 edgeTreatment 用库默认 TileMode.Clamp（边像素外扩），
            // 所以屏幕最外圈不会透出底下那张清晰的 ①。
            if (bgBlurDp > 0.dp) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawPlainBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = { blur(bgBlurDp.toPx()) },
                        )
                )
            }

            // ── ③ 纱层：压黑 + 导轨渐变 + 内容页底图那层面板色 ──
            AppBackdropScrim(
                panorama = panorama,
                railWidth = railW,
                darkScrim = isDarkTheme,
                hasPageBg = pageBg != null,
            )

            // ── ④ 涟漪那道**半透明分界框** ──
            //
            // 用户要求：圆形分界线不要模糊带，改半透明框。
            // 所以它只能是一道描边（一次 stroke），不贴位图 —— 比原先那 12 层
            // 全屏淡出贴图便宜得多。放在纱**之后**，不然玻璃边的高光会被压黑吃掉。
            RippleRing(frame = rippleFrame)

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
) {
    Box(Modifier.fillMaxSize()) {
        if (panorama) {
            HomeSceneBackdrop(wallpaper = wallpaper)
        } else {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
        }

        if (pageBg != null) {
            Box(Modifier.fillMaxSize().padding(start = railWidth)) {
                // 同样**不糊** —— 这张也在采样层里，卡片要折得出细节。
                // 模糊统一交给上面 ② 那一趟重放（整层一起糊，比例自然一致）。
                Image(
                    bitmap = pageBg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
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
 * 涟漪的几何：算出这个圆心（px）和当前半径（px）。
 *
 * 采样层那份（贴新图）和分界框那份（描边）要用**一模一样**的数，
 * 不然圈和图对不上 —— 所以抽出来共用。
 * ⚠️ Kotlin 不允许局部 data class（坑 #26），这里用 Triple。
 */
private fun rippleGeom(
    w: Float,
    h: Float,
    r: lo.naui.ui.theme.BgRipples.Ripple,
    now: Long,
): Triple<Float, Float, Float> {
    val cx = w * r.cx
    val cy = h * r.cy
    val maxR = maxOf(
        kotlin.math.hypot(cx, cy),
        kotlin.math.hypot(w - cx, cy),
        kotlin.math.hypot(cx, h - cy),
        kotlin.math.hypot(w - cx, h - cy),
    )
    val pr = lo.naui.ui.theme.BgRipples.progressOf(r, now)
    return Triple(cx, cy, (maxR * pr).coerceAtLeast(1f))
}

/**
 * 切背景时**推出来的那张新图**（画在采样层里，所以卡片会跟着一起变）。
 *
 * ## 1.00.0 第二次改
 *
 * **① 不再自己糊。** 上一版给整个 Canvas 挂了一道 blur，现在整层模糊由外壳
 * 用"重放录制层"那一趟统一做（AppShell 的 ②层）—— 少一次全屏模糊。
 *
 * **② 不再画那 12 层假模糊带。** 用户明确要求：圆形分界线不要模糊，改半透明框。
 * 淡出带整个删掉，边上一道 stroke 由 [RippleRing] 画 —— 一次描边 vs 十二次全屏贴图。
 *
 * **③ 底层那张旧图不铺。** 下面 ① 层已经画过同一张壁纸，重铺纯属白烧；
 * Canvas 本来就透明，圆外自然露出旧图。
 *
 * **④ 帧循环挪走了。** 原来是 `LaunchedEffect(Unit){ while(true){ delay(33) } }`
 * 常驻空转（而且这层被画两遍 = 两个协程）。现在驱动在外壳那一个循环里，
 * 帧号只在**绘制阶段**读，每帧只让这一个节点重绘，不触发重组。
 */
@Composable
private fun RippleLayer(
    /** 底层现在显示的那张 —— 为 null 说明还没图，那就不用过渡 */
    shown: ImageBitmap?,
    /** 外壳的帧号 */
    frame: androidx.compose.runtime.MutableIntState,
) {
    if (!lo.naui.ui.theme.BgRipples.running || shown == null) return

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frame.intValue

        val now = System.currentTimeMillis()
        val ripples = lo.naui.ui.theme.BgRipples.active(now)
        if (ripples.isEmpty()) return@Canvas

        val dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())

        ripples.forEach { r ->
            val (cx, cy, radius) = rippleGeom(size.width, size.height, r, now)
            val img = r.to

            // 按 Crop 取源矩形 —— 跟背景那张 Image(contentScale = Crop) 对齐。
            // 原来整张拉满（FillBounds），同一张图两种裁法，圆的边上看得见错位，
            // 看着就像"新图直接投影上去的"。
            val scale = maxOf(size.width / img.width, size.height / img.height)
            val srcW = (size.width / scale).toInt().coerceIn(1, img.width)
            val srcH = (size.height / scale).toInt().coerceIn(1, img.height)
            val srcOff = androidx.compose.ui.unit.IntOffset(
                (img.width - srcW) / 2, (img.height - srcH) / 2
            )
            val srcSz = androidx.compose.ui.unit.IntSize(srcW, srcH)

            // 包围盒裁一下 —— clipRect 便宜，clipPath 会强制全屏离屏（坑 #45）
            clipRect(
                left = (cx - radius).coerceAtLeast(0f),
                top = (cy - radius).coerceAtLeast(0f),
                right = (cx + radius).coerceAtMost(size.width),
                bottom = (cy + radius).coerceAtMost(size.height),
            ) {
                val circle = androidx.compose.ui.graphics.Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            cx - radius, cy - radius, cx + radius, cy + radius
                        )
                    )
                }
                clipPath(circle) {
                    drawImage(
                        image = img,
                        srcOffset = srcOff,
                        srcSize = srcSz,
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = dstSize,
                    )
                }
            }
        }
    }
}

/**
 * 切背景那道**半透明分界框** —— 用户要的"不用模糊、用半透明框"。
 *
 * 只描边、不贴位图：一道宽环打底 + 一道亮细线当玻璃边，随进度淡出。
 * 画在纱层**之后**（不然高光被压黑吃掉）、内容层之前（卡片该盖在圈上面）。
 */
@Composable
private fun RippleRing(frame: androidx.compose.runtime.MutableIntState) {
    if (!lo.naui.ui.theme.BgRipples.running) return

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frame.intValue

        val now = System.currentTimeMillis()
        val ripples = lo.naui.ui.theme.BgRipples.active(now)
        if (ripples.isEmpty()) return@Canvas

        ripples.forEach { r ->
            val (cx, cy, radius) = rippleGeom(size.width, size.height, r, now)
            val a = lo.naui.ui.theme.BgRipples.edgeAlphaOf(r, now)
            if (a <= 0.001f) return@forEach
            val c = androidx.compose.ui.geometry.Offset(cx, cy)

            // 宽环：半透明的"框"本体
            drawCircle(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f * a),
                radius = radius,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 30f),
            )
            // 里圈一道亮线：玻璃边的高光
            drawCircle(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.42f * a),
                radius = radius,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f),
            )
        }
    }
}

