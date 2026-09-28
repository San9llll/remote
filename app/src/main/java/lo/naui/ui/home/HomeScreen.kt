package lo.naui.ui.home

import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import lo.naui.sys.Privilege
import lo.naui.sys.TopPrivilege
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalTime

/**
 * 首页（全景）。
 *
 * 整页壁纸 + 左侧场景导轨（时钟 / 电量 / 信息）→ hero 卡 → 问候 → 一张卡。
 *
 * 那张卡上只放**当前能拿到的最高权限**，root 和 Shizuku 不会同时出现：
 * 有 root 就写 APatch / KernelSU / Magisk + 对应二进制的版本，
 * 只有 Shizuku 就写 Shizuku + adb 版本，普通用户这一块整个不显示。
 */
@Composable
fun HomeScreen(
    railWidth: Dp = 0.dp,
    wallpaper: ImageBitmap? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val context = LocalContext.current

    var top by remember { mutableStateOf<TopPrivilege?>(null) }

    LaunchedEffect(Unit) {
        // 权限可能中途变（比如刚给 Shizuku 授权），隔一会儿看一眼
        while (true) {
            top = Privilege.topInfo(context)
            delay(5000)
        }
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

            // 当前最高权限 + 设备/系统，合成一张卡
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = 18.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    val p = top
                    if (p != null) {
                        Text(
                            p.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            p.subtitle,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )

                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MiuixTheme.colorScheme.dividerLine)
                        )
                        Spacer(Modifier.height(14.dp))
                    }

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
