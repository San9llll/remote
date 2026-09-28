// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.delay
import lo.naui.ui.nav.Dest
import lo.naui.ui.theme.ClockStyle
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
 */
@Composable
fun HomeSceneRail(
    destinations: List<Dest>,
    current: Dest,
    onSelect: (Dest) -> Unit,
    clockStyle: ClockStyle,
    wallpaper: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeModeState.current.isDark

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
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < SceneShortHeight
            val iconsOnly = compact && maxHeight < SceneRailLabelledHeight

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
                    SceneBattery()
                }

                Spacer(Modifier.weight(1f))

                // 全部导航项都在（含「主页」）
                destinations.forEach { d ->
                    SceneRailItem(
                        selected = current == d,
                        icon = d.icon,
                        label = d.label,
                        showLabel = !iconsOnly,
                        compact = compact,
                        onClick = { onSelect(d) },
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

@Composable
private fun SceneRailItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    compact: Boolean,
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
