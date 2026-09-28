package lo.naui.ui.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.component.GlassCard
import lo.naui.ui.theme.GlobalLayout
import lo.naui.ui.theme.Prefs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalTime

/**
 * 首页（全景）。
 *
 * 整页模糊壁纸 + 左侧场景导轨（时钟和电量在导轨上），
 * 中间是壁纸 hero 卡（底部渐变淡出）、问候、状态条，然后是一摞玻璃卡。
 *
 * 这一页是**本地**的：没有网络请求，内容都是本机的东西。
 */
@Composable
fun HomeScreen(
    railWidth: Dp = 0.dp,
    wallpaper: ImageBitmap? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val panorama = (Prefs.current?.globalLayout ?: GlobalLayout.Panorama) == GlobalLayout.Panorama

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val layout = homeSceneLayout(
            width = maxWidth,
            height = (maxHeight - topInset - bottomInset).coerceAtLeast(0.dp),
        )
        val scrollState = rememberScrollState()

        // 整页模糊壁纸背景（Aster 的 HomeSceneBackdrop）
        if (panorama) {
            HomeSceneBackdrop(wallpaper = wallpaper, railWidth = railWidth)
        }

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

            SceneStatusStrip(
                leftTitle = "设备",
                leftValue = Build.MANUFACTURER + " " + Build.MODEL,
                rightTitle = "系统",
                rightValue = "Android " + Build.VERSION.RELEASE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 18.dp),
            )

            Column(
                Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassCard(
                    backdrop = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 18.dp,
                ) {
                    Column {
                        Text(
                            "外壳已经就位",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "玻璃卡走的是纯 2D 叠层，一张真 backdrop 都没铺",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
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
