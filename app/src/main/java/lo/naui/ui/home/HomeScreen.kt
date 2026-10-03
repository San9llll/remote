package lo.naui.ui.home

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import android.os.Build
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lo.naui.sys.Metrics
import lo.naui.sys.Privilege
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalTime

/**
 * 首页（全景）。
 *
 * 文案按用户要求调过：
 *  - hero 卡上是大字的 **Nakour**（原来放的是 Late night）
 *  - 问候那一行改成放时间词（原来放的是 Nakour）
 *  - 日语下面补一行低透明度的中文翻译
 *  - "Nakour · 本地版"那行副标题删了
 */
@Composable
fun HomeScreen(
    railWidth: Dp = 0.dp,
    wallpaper: ImageBitmap? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 上滑换大图要用
    val prefs = lo.naui.ui.theme.Prefs.current

    var refreshing by remember { mutableStateOf(false) }
    // 拉一次就把设备/权限信息重新问一遍，这样"下拉刷新"是真有东西刷新的
    var deviceLine by remember { mutableStateOf("") }
    var lspLine by remember { mutableStateOf("") }

    suspend fun reload() {
        deviceLine = Build.MANUFACTURER + " " + Build.MODEL
        // 右边那栏显示 LSPosed 的状态 —— 用户要的"LSP 权限"
        lspLine = when {
            lo.naui.sys.XposedActive.isActive(ctx) -> {
                val seen = lo.naui.sys.XposedActive.lastSeen()
                if (seen.isBlank()) "已生效" else "已生效 · " + seen
            }
            runCatching { Privilege.level(ctx) }.getOrDefault(lo.naui.sys.PrivLevel.Normal) !=
                lo.naui.sys.PrivLevel.Normal -> runCatching { Privilege.level(ctx).label }
                .getOrDefault("未生效")
            else -> "未生效"
        }
        delay(320)
    }

    LaunchedEffect(Unit) { reload() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val layout = homeSceneLayout(
            width = maxWidth,
            height = (maxHeight - topInset - bottomInset).coerceAtLeast(0.dp),
        )
        val scrollState = rememberScrollState()

        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    reload()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
            refreshTexts = listOf("下拉刷新", "松手刷新", "刷新中…", "刷新完成"),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                Spacer(Modifier.height(12.dp))

                // hero 卡：大字是 Nakour（原来这儿放的是 Late night）
                // hero 卡：大字是 Nakour（原来这儿放的是 Late night）
                //
                // 上滑换一张大图 —— 用户要的"上滑刷新"。
                // 手势只挂在 hero 上，不跟下面那个下拉刷新打架。
                SceneHero(
                    wallpaper = wallpaper,
                    title = "",
                    subtitle = "",
                    badge = null,
                    scrollState = scrollState,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .height(layout.heroHeight)
                        .pointerInput(Unit) {
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dy = 0f },
                                onDragEnd = {
                                    // 往上滑够了就换 —— 阈值小一点，手感轻
                                    if (dy < -60f) prefs?.nextHero(ctx)
                                },
                            ) { _, drag -> dy += drag }
                        },
                )

                // 问候：上面小字改成时间词（原来是 Nakour），下面是日语 + 中文翻译
                SceneGreeting(
                    greetingLine = greet().first,
                    quote = greet().second,
                    translation = greet().third,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(top = 18.dp),
                )

                SceneStatusStrip(
                    leftTitle = "设备",
                    leftValue = deviceLine.ifBlank { Build.MANUFACTURER + " " + Build.MODEL },
                    rightTitle = "LSP 权限",
                    rightValue = lspLine.ifBlank { "—" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 18.dp),
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * 问候三件套：时间词 / 日语 / 中文翻译。
 *
 * 用户要的是"日语下方左侧有中文小字低透明度翻译"。
 */
private fun greet(): Triple<String, String, String> {
    val h = LocalTime.now().hour
    return when (h) {
        in 0..4 -> Triple("Late night", "夢の続きを、もう少しだけ", "让梦再续一会儿")
        in 5..10 -> Triple("Good morning", "良い一日になりますように", "愿你今天顺顺利利")
        in 11..13 -> Triple("Good noon", "お昼だね、ちゃんと食べた？", "中午了，有好好吃饭吗")
        in 14..17 -> Triple("Good afternoon", "午後のひとときを、ゆっくりと", "午后这段时光，慢慢来")
        in 18..22 -> Triple("Good evening", "今日も一日、お疲れさま", "今天也辛苦了")
        else -> Triple("Good night", "そろそろ休もう？", "差不多该休息了吧")
    }
}
