package lo.naui.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.sys.Metrics
import lo.naui.sys.RootInfo
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalTime

/**
 * 首页（全景）。
 *
 * 整页模糊壁纸 + 左侧场景导轨（时钟 / 电量 / 信息都在导轨上），
 * 中间是壁纸 hero 卡（底部渐变淡出）、问候，然后一张卡：
 *
 *   上面一行是**拿到的 root 是什么、什么版本**
 *   下面接着是设备 / 系统那条状态条
 *
 * 底图（整页壁纸）是外壳画在背景层里的，这一页只管内容 ——
 * 卡片要采样的是那一层，所以卡片不能再被标成 backdrop 的源（不然就递归了）。
 */
@Composable
fun HomeScreen(
    railWidth: Dp = 0.dp,
    wallpaper: ImageBitmap? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    var root by remember { mutableStateOf(RootInfo()) }
    LaunchedEffect(Unit) {
        // 内部有缓存，整个 App 生命周期只会去问一次 su
        root = Metrics.rootInfo()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val layout = homeSceneLayout(
            width = maxWidth,
            height = (maxHeight - topInset - bottomInset).coerceAtLeast(0.dp),
        )
        val scrollState = rememberScrollState()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Spacer(Modifier.height(12.dp))

            // hero 卡：不挂时钟（时钟在左侧导轨上）
            SceneHero(
                wallpaper = wallpaper,
                title = greet().first,
                subtitle = "Nakour · 本地版",
                badge = null,
                scrollState = scrollState,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .height(layout.heroHeight),
            )

            SceneGreeting(
                greetingLine = "Nakour",
                quote = greet().second,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 18.dp),
            )

            // root + 设备/系统 合成一张卡
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = 18.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    RootRow(root)

                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MiuixTheme.colorScheme.dividerLine)
                    )
                    Spacer(Modifier.height(14.dp))

                    SceneStatusStrip(
                        leftTitle = "设备",
                        leftValue = Build.MANUFACTURER + " " + Build.MODEL,
                        rightTitle = "系统",
                        rightValue = "Android " + Build.VERSION.RELEASE,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一行：小圆点 + 「root 方式 + 版本」 + 副标题 + 右边一个 ROOT 标记 */
@Composable
private fun RootRow(root: RootInfo) {
    val accent = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (root.granted) accent else muted.copy(alpha = 0.45f))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (root.version.isBlank()) root.name else root.name + " " + root.version,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(root.detail, fontSize = 12.sp, color = muted)
        }
        Text(
            if (root.granted) "ROOT" else "无 ROOT",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (root.granted) accent else muted,
        )
    }
}

private fun greet(): Pair<String, String> {
    val h = LocalTime.now().hour
    return when (h) {
        in 0..4 -> "Late night" to "夢の続きを、もう少しだけ"
        in 5..10 -> "Good morning" to "良い一日になりますように"
        in 11..13 -> "Good noon" to "お昼だね、ちゃんと食べた？"
        in 14..17 -> "Good afternoon" to "午後のひとときを、ゆっくりと"
        in 18..22 -> "Good evening" to "今日も一日、お疲れさま"
        else -> "Good night" to "そろそろ休もう？"
    }
}
