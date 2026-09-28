package lo.naui.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import lo.naui.ui.home.HomeSceneBackdrop
import lo.naui.ui.home.HomeSceneRail
import lo.naui.ui.home.HomeScreen
import lo.naui.ui.nav.Dest
import lo.naui.ui.agent.AgentConfigScreen
import lo.naui.ui.agent.AgentScreen
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

private enum class Sub { None, Theme, About, Files, AgentConfig }

/**
 * 外壳 —— 结构对齐参考项目（Aster 的 AsterAppShell）：
 *
 * - 内容整层挂在 `layerBackdrop` 上（底栏的液态玻璃才有东西可以折射）
 * - **Panorama + 首页**：左边那条场景导轨（时钟 / 电量 / 导航），首页另有整页模糊壁纸背景
 * - **Panorama + 其他页**：一样走左侧导轨，导航位置始终一致
 * - **Standard**：窄屏底部 NavigationBar，宽屏侧边 NavigationRail
 */
@Composable
fun AppShell(prefs: ThemePrefs) {
    var current by remember { mutableStateOf(Dest.Home) }
    var sub by remember { mutableStateOf(Sub.None) }

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
    // 液态玻璃模式下，背景层要交出清晰图给 drawBackdrop 去糊
    val liquidGlass = prefs.cardStyle == CardStyle.Liquid

    // Agent 那个入口可以在「主题 → 导航与外壳 → 侧栏设置」里关掉
    val railDests = if (prefs.railShowOverview) NAV_DESTS else NAV_DESTS.filter { it != Dest.Overview }

    val showSceneRail = panorama
    val standardBottomBar = !panorama && useBottomNav
    val standardRail = !panorama && !useBottomNav

    // 主图在 shell 层加载一次：导轨拿它做背景，首页拿它画大图。
    // 图是**本地**的（在主题页里自选），不从服务器拉。
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    // 内容页背景图（模块 / 概览 / 设置铺的那张）—— 从主题页选的本地图读
    var pageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(prefs.homeImage) {
        // 取色要的是 android.graphics.Bitmap，所以原图先留着，画的时候再转 ImageBitmap
        val bmp = loadBitmap(prefs.homeImage)
        if (bmp != null) {
            wallpaper = bmp.asImageBitmap()
            val (seed, isLight) = lo.naui.ui.theme.dominantSeed(bmp)
            prefs.saveWallpaperSeed(seed, isLight)
        } else {
            wallpaper = null
        }
    }

    LaunchedEffect(prefs.contentImage) {
        pageBitmap = loadBitmap(prefs.contentImage)?.asImageBitmap()
    }

    // 返回键先回上一级，别一按就退出 App
    BackHandler(enabled = sub != Sub.None || current != Dest.Home) {
        if (sub != Sub.None) {
            sub = Sub.None
        } else {
            current = Dest.Home
        }
    }

    val backdrop = rememberLayerBackdrop()

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
                    backdrop = backdrop,
                )
                Dest.Overview -> AgentScreen(
                    onOpenConfig = { sub = Sub.AgentConfig },
                )
                Dest.Settings -> SettingsScreen(
                    prefs = prefs,
                    onOpenTheme = { sub = Sub.Theme },
                    onOpenAbout = { sub = Sub.About },
                )
            }
            Sub.Theme -> ThemeScreen(prefs, onBack = { sub = Sub.None })
            Sub.About -> AboutScreen(onBack = { sub = Sub.None })
            Sub.Files -> FileManagerScreen(onBack = { sub = Sub.None })
            Sub.AgentConfig -> AgentConfigScreen(onBack = { sub = Sub.None })
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // ① 背景层 —— 这一层才是 backdrop 的「源」：
            //    整页壁纸 + 内容页底图都记在这里。
            //    ⚠️ 卡片绝不能待在这一层里面（那样就成了"背景画内容、内容又画背景"的
            //       无限递归，渲染线程直接 SIGSEGV）。它只能在**外面**用 drawBackdrop 采样。
            //    口诀：layerBackdrop 标背景，drawBackdrop 画玻璃，两者不可互相套。
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                if (panorama) {
                    HomeSceneBackdrop(
                        wallpaper = wallpaper,
                        railWidth = if (showSceneRail) sceneRailWidth else 0.dp,
                        // 液态玻璃模式下这层放清晰图 —— 模糊交给玻璃卡里的 blur 做。
                        // 这层要是自己先糊一遍再压黑纱，玻璃卡折射出来就是一团黑。
                        sharp = liquidGlass,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
                }

                // 内容页底图：只铺在「功能 / 概览 / 设置」这几页，主页和侧边栏不受影响
                val showPageBg = sub == Sub.None && current != Dest.Home && pageBitmap != null
                if (showPageBg) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = if (showSceneRail) sceneRailWidth else 0.dp),
                    ) {
                        Image(
                            bitmap = pageBitmap!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                MiuixTheme.colorScheme.surface.copy(
                                    alpha = if (prefs.darkMode == lo.naui.ui.theme.DarkMode.Dark) 0.72f else 0.55f
                                )
                            )
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
                        // 页面切换动画：切 tab 当一张长图上下滚，进子页从卡片左下角放大展开
                        AnimatedContent(
                            // 必须给尺寸：不给的话子项里的滚动容器
                            // 会拿到无限高度约束，直接抛 IllegalStateException
                            modifier = Modifier.fillMaxSize(),
                            targetState = current to sub,
                            transitionSpec = {
                                // 进子页：从**左下角**放大展开
                                // ——就是卡片左下那个按钮的位置，"从这里长出来"的感觉
                                if (targetState.second != Sub.None) {
                                    (
                                        fadeIn(tween(260)) +
                                            scaleIn(
                                                animationSpec = tween(360),
                                                initialScale = 0.16f,
                                                transformOrigin = TransformOrigin(0.06f, 0.94f),
                                            )
                                        ).togetherWith(fadeOut(tween(180)))
                                } else {
                                    // 切 tab：当一张长图在上下滚
                                    // 旧页整屏往上滑走，新页从下面整屏顶上来；两页不重叠、不加淡入
                                    // 方向跟着 tab 顺序走（往右切 = 往下滚）
                                    val forward = targetState.first.ordinal >= initialState.first.ordinal
                                    val dir = if (forward) 1 else -1
                                    val spec = tween<IntOffset>(
                                        durationMillis = 460,
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
                    clockStyle = prefs.clockStyle,
                    wallpaper = wallpaper,
                    showBattery = prefs.railShowBattery,
                    showInfo = prefs.railShowInfo,
                    infoLines = prefs.railInfoList(),
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
