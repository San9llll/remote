// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.home

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import lo.naui.sys.Metrics
import lo.naui.sys.MetricsSnapshot
import lo.naui.sys.Shortcut
import lo.naui.ui.nav.Dest
import lo.naui.ui.theme.ClockStyle
import lo.naui.ui.theme.InfoMetric
import lo.naui.ui.theme.LocalThemeModeState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 站在壁纸上的白（和 SceneClock 用同一套） */
private val SceneOnWallpaper = Color.White
private val SceneTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.32f),
    offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
    blurRadius = 6f,
)

/** 窗口矮于这个高度，导轨就不放时钟和电量了（对齐 Aster 的 SceneShortHeight） */
private val SceneShortHeight = 560.dp

/** 再矮一点，连标签也放不下 */
private val SceneRailLabelledHeight = 330.dp

/**
 * 场景导轨 —— 浮在壁纸左边的竖排导航。
 *
 * 背景是**那张主页大图的模糊版**（整条导轨自己画一层），再按明暗压一层黑：
 * 深色模式下压得更重，所以白字在亮照片上也站得住。
 * 右边用横向渐变化开，和主页的模糊背景自然接上，不会切出一条硬边。
 *
 * 自上而下：时钟 → 电量（可隐藏）→ 信息块（可隐藏）→ 导航项。
 * 信息块**不是玻璃卡**，就是一块带上下细线的纯文字区，不走液态玻璃那套折射。
 */
@Composable
fun HomeSceneRail(
    destinations: List<Dest>,
    current: Dest,
    onSelect: (Dest) -> Unit,
    /**
     * 点的那一项在**屏幕上的真实坐标**（归一化 0~1），方案 B 拿它当扩散圆心。
     * 两个数分别是 x、y。
     */
    onSelectAt: (Dest, Float, Float) -> Unit = { d, _, _ -> onSelect(d) },
    clockStyle: ClockStyle,
    wallpaper: ImageBitmap? = null,
    showBattery: Boolean = true,
    showInfo: Boolean = true,
    infoLines: List<InfoMetric> = emptyList(),
    /** 功能快捷栏（默认空 = 隐藏） */
    shortcuts: List<Shortcut> = emptyList(),
    onShortcutClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeModeState.current.isDark
    val context = LocalContext.current

    // 信息块要的那几个数：进来之后每 2 秒采一次
    var snapshot by remember { mutableStateOf(MetricsSnapshot()) }
    val activeLines = remember(infoLines) { infoLines.filter { it != InfoMetric.None } }

    LaunchedEffect(showInfo, activeLines) {
        if (!showInfo || activeLines.isEmpty()) return@LaunchedEffect
        while (true) {
            snapshot = Metrics.sample(context)
            // 6 秒一次就够 —— 这些数是给人扫一眼的，采太勤纯费电
            delay(6000)
        }
    }

    Box(modifier) {
        // ---- 导轨自己的背景 ----
        Box(Modifier.fillMaxSize()) {
            if (wallpaper != null) {
                Image(
                    bitmap = wallpaper,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(40.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                MiuixTheme.colorScheme.primaryContainer,
                                MiuixTheme.colorScheme.secondaryContainer,
                            )
                        )
                    )
                )
            }
            // 明暗决定压多黑
            Box(
                Modifier.fillMaxSize().background(
                    Color.Black.copy(alpha = if (isDark) 0.52f else 0.34f)
                )
            )
            // 右边化开，和主页背景接上
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.78f to Color.Transparent,
                            1f to Color.Black.copy(alpha = if (isDark) 0.35f else 0.22f),
                        )
                    )
                )
            )
            // ---- 彩色流动 ----
            // 一层慢慢游走的彩色渐变，盖在模糊壁纸上。
            // 用户要的是"色彩动态变化、模糊保持不变" —— 所以模糊那层一个字没动，
            // 只是上面多了一层会呼吸的颜色。
            //
            // ⚠️ 这里**故意不用 rememberInfiniteTransition**。
            // 那个是每帧（60fps）驱动一次重组，而导轨是常驻屏幕的 ——
            // 等于整机一直有 60fps 的 recomposition 在跑，手机扛不住会降频保护
            // （实机反馈：打开应用 CPU 被压到 480MHz）。
            // 改成自己用 delay 驱动，约 8fps，肉眼看一样顺，CPU 掉八成。
            var shift by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(Unit) {
                val t0 = System.currentTimeMillis()
                while (true) {
                    shift = ((System.currentTimeMillis() - t0) % 9000L) / 9000f
                    delay(120)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val h = size.height
                        val w = size.width
                        // 让渐变沿着导轨慢慢往下走
                        val y0 = -h * 0.5f + h * 2f * shift
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF7C4DFF).copy(alpha = 0.34f),   // 紫
                                Color(0xFF00E5FF).copy(alpha = 0.26f),   // 青
                                Color(0xFFFF4D8D).copy(alpha = 0.32f),   // 粉
                                Color(0xFF7C4DFF).copy(alpha = 0.34f),   // 绕回紫，首尾接得上
                            ),
                            start = androidx.compose.ui.geometry.Offset(0f, y0),
                            end = androidx.compose.ui.geometry.Offset(w, y0 + h * 0.9f),
                        )
                        onDrawBehind { drawRect(brush) }
                    },
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < SceneShortHeight
            val iconsOnly = compact && maxHeight < SceneRailLabelledHeight

            // ⚠️ 归一化必须用**整块屏幕**的尺寸，不能用这条导轨自己的 ——
            // 导轨只有 68dp 宽，拿它当分母算出来的坐标是错的（方案 B 的 bug 就在这儿）
            val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
                .coerceAtLeast(1)
            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                .coerceAtLeast(1)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (compact) 10.dp else 28.dp))

                if (!compact) {
                    SceneClock(clockStyle)
                    Spacer(Modifier.height(24.dp))
                    if (showBattery) {
                        SceneBattery()
                    }
                    if (showInfo && activeLines.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        RailInfoBlock(items = activeLines, snap = snapshot)
                    }
                }

                Spacer(Modifier.weight(1f))

                // 功能快捷栏：夹在信息块和导航项中间，自己独立上下滑
                // 外面套一层有界高度的 Box —— 不定高度的话里面那个 LazyColumn
                // 会拿到无限约束，直接抛 "infinity maximum height constraints"
                AnimatedVisibility(
                    visible = shortcuts.isNotEmpty(),
                    enter = slideInHorizontally(tween(340)) { -it } + fadeIn(tween(260)),
                    exit = slideOutHorizontally(tween(240)) { -it } + fadeOut(tween(160)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(horizontal = 8.dp),
                    ) {
                        LazyColumn(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(shortcuts, key = { it.key }) { sc ->
                                RailShortcutBarItem(
                                    title = sc.title,
                                    onClick = { onShortcutClick(sc.key) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 全部导航项都在（含「主页」）—— 概览那个入口可以在侧栏设置里关掉
                destinations.forEach { d ->
                    var cx by remember { mutableStateOf(0f) }
                    var cy by remember { mutableStateOf(0f) }
                    SceneRailItem(
                        selected = current == d,
                        icon = d.icon,
                        label = d.label,
                        showLabel = !iconsOnly,
                        compact = compact,
                        onPositioned = { x, y -> cx = x; cy = y },
                        onClick = {
                            onSelectAt(
                                d,
                                (cx / screenW).coerceIn(0f, 1f),
                                (cy / screenH).coerceIn(0f, 1f),
                            )
                        },
                    )
                }

                if (compact) {
                    Spacer(Modifier.weight(1f))
                }

                Spacer(
                    Modifier
                        .height(if (compact) 12.dp else 20.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                )
            }
        }
    }
}

/* ---------------- 信息块 ---------------- */

/**
 * 左栏信息块。
 *
 * 上下各一条 1dp 的细线把它的范围标出来 —— 这两条线是**块自己的边界**，
 * 不是卡片描边，所以它不参与液态玻璃那套折射。
 */
@Composable
private fun RailInfoBlock(items: List<InfoMetric>, snap: MetricsSnapshot) {
    val rule = SceneOnWallpaper.copy(alpha = 0.32f)
    val labelColor = SceneOnWallpaper.copy(alpha = 0.58f)
    val valueColor = SceneOnWallpaper.copy(alpha = 0.92f)

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        // 上细线
        Box(Modifier.fillMaxWidth().height(1.dp).background(rule))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items.forEach { metric ->
                val label = metricLabel(metric, snap)
                val value = metricValue(metric, snap)
                if (metric.wide) {
                    // 窄导轨里一行放不下，拆两行
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            label,
                            fontSize = 8.5.sp,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            value,
                            fontSize = 10.sp,
                            color = valueColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            fontSize = 8.5.sp,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            value,
                            fontSize = 10.sp,
                            color = valueColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 下细线
        Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
    }
}

private fun metricLabel(m: InfoMetric, snap: MetricsSnapshot): String = when (m) {
    InfoMetric.BatteryTemp -> "电池"
    InfoMetric.CpuTemp -> "CPU"
    InfoMetric.Ram -> "内存"
    InfoMetric.CpuUsage -> "占用"
    InfoMetric.GpuUsage -> "GPU"
    InfoMetric.BatteryPower -> "功率"
    InfoMetric.BatteryVi -> "电压电流"
    InfoMetric.Network -> snap.carrier.ifBlank { "网络" }
    InfoMetric.None -> ""
}

private fun metricValue(m: InfoMetric, snap: MetricsSnapshot): String = when (m) {
    InfoMetric.BatteryTemp -> snap.batteryTempC?.let { one(it) + "°" } ?: "—"
    InfoMetric.CpuTemp -> snap.cpuTempC?.let { zero(it) + "°" } ?: "—"
    InfoMetric.Ram -> snap.ramPercent?.let { zero(it) + "%" } ?: "—"
    InfoMetric.CpuUsage -> snap.cpuUsagePercent?.let { zero(it) + "%" } ?: "—"
    InfoMetric.GpuUsage -> snap.gpuPercent?.let { zero(it) + "%" } ?: "—"
    InfoMetric.BatteryPower -> snap.batteryPowerW?.let {
        // 充电是正的、放电是负的，正数前面补个 + 看着更明确
        (if (it >= 0f) "+" else "-") + one(abs(it)) + "W"
    } ?: "—"
    InfoMetric.BatteryVi -> {
        val v = snap.batteryVoltageV
        val a = snap.batteryCurrentA
        if (v != null && a != null) two(v) + "V " + two(a) + "A" else "—"
    }
    InfoMetric.Network -> snap.netRateText
    InfoMetric.None -> ""
}

private fun zero(v: Float): String = v.roundToInt().toString()

private fun one(v: Float): String = ((v * 10f).roundToInt() / 10f).toString()

private fun two(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()

/* ---------------- 功能快捷栏 ---------------- */

@Composable
private fun RailShortcutBarItem(title: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SceneOnWallpaper.copy(alpha = 0.16f))
            .border(1.dp, SceneOnWallpaper.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            fontSize = 10.sp,
            color = SceneOnWallpaper.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/* ---------------- 导航项 ---------------- */

@Composable
private fun SceneRailItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    compact: Boolean,
    onPositioned: (Float, Float) -> Unit = { _, _ -> },
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.74f,
        animationSpec = tween(220),
        label = "rail_alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                // 把自己中心点在窗口里的绝对坐标报出去
                val p = coords.positionInWindow()
                onPositioned(
                    p.x + coords.size.width / 2f,
                    p.y + coords.size.height / 2f,
                )
            }
            .padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 2.dp else 4.dp,
            )
            .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        // 选中态的背景渐变过去，而不是一下子跳出来
        val bg by animateColorAsState(
            targetValue = if (selected) SceneOnWallpaper.copy(alpha = 0.22f) else Color.Transparent,
            animationSpec = tween(220),
            label = "rail_sel",
        )
        Box(
            modifier = Modifier
                .size(if (compact) 30.dp else 38.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = if (showLabel) null else label,
                modifier = Modifier.size(if (compact) 20.dp else 23.dp),
                tint = SceneOnWallpaper.copy(alpha = alpha),
            )
        }
        // 照片上的图标比主题栏里更需要标签：没人能从形状认出"数据维护"或"运行统计"
        if (showLabel) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = SceneOnWallpaper.copy(alpha = alpha * 0.88f),
                    shadow = SceneTextShadow,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 电量。画的是一颗电池：外框 + 按比例填充 + 顶上那个小凸起，
 * 旁边写百分比。数字和状态栏重复，所以矮导轨会把它丢掉。
 */
@Composable
private fun SceneBattery() {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(readBatteryPercent(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            percent = readBatteryPercent(context)
            delay(60_000)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(14.dp)
                .border(
                    width = 1.5.dp,
                    color = SceneOnWallpaper.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(2.dp),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .background(SceneOnWallpaper.copy(alpha = 0.86f), RoundedCornerShape(2.dp))
            )
        }
        Text(
            text = percent.toString() + "%",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = SceneOnWallpaper.copy(alpha = 0.9f),
                shadow = SceneTextShadow,
            ),
        )
    }
}

private fun readBatteryPercent(context: Context): Int = runCatching {
    val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return 0
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) 0 else (level * 100 / scale)
}.getOrDefault(0)
