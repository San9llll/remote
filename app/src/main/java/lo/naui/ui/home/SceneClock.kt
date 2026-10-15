// Adapted from Aster (LyraVoid/Aster, GPL-3.0)
package lo.naui.ui.home

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Text

/** 站在壁纸上的字（对齐 Aster 的 SceneOnWallpaper / SceneTextShadow） */
private val SceneOnWallpaper = Color.White
private val SceneTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.32f),
    offset = Offset(0f, 1.5f),
    blurRadius = 6f,
)

private val ClockFormatterHour = DateTimeFormatter.ofPattern("HH")
private val ClockFormatterHour12 = DateTimeFormatter.ofPattern("h")
private val ClockFormatterMinute = DateTimeFormatter.ofPattern("mm")
private val ClockFormatterTime = DateTimeFormatter.ofPattern("HH:mm")
private val ClockFormatterTime12 = DateTimeFormatter.ofPattern("h:mm")

/** 单独的小时，按 [is24Hour] 的形状 */
fun sceneHourText(time: LocalTime, is24Hour: Boolean): String =
    time.format(if (is24Hour) ClockFormatterHour else ClockFormatterHour12)

/** 完整时间，按 [is24Hour] 的形状 */
fun sceneTimeText(time: LocalTime, is24Hour: Boolean): String =
    time.format(if (is24Hour) ClockFormatterTime else ClockFormatterTime12)

/** 上下午，24 小时制下是 null（那个数字已经说完了） */
fun sceneMeridiemText(time: LocalTime, is24Hour: Boolean, locale: Locale): String? =
    if (is24Hour) null else time.format(DateTimeFormatter.ofPattern("a", locale))

/**
 * 手机读不读 12 小时制。这是用户能在场景已经显示着的时候随手改的设置，
 * 所以是盯着它而不是在组合时读一次。
 */
@Composable
private fun rememberSystem24Hour(): Boolean {
    val context = LocalContext.current
    var is24 by remember { mutableStateOf(DateFormat.is24HourFormat(context)) }
    androidx.compose.runtime.DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                is24 = DateFormat.is24HourFormat(context)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.TIME_12_24),
                false,
                observer,
            )
        }.isSuccess
        onDispose {
            if (registered) {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }
    return is24
}

/**
 * 场景时钟。四种形状差别是真差别，而不是一个设计加几个开关：
 * 两行数字 / 单行数字 / 指针 / 只画日期。
 */
@Composable
fun SceneClock(style: lo.naui.ui.theme.ClockStyle, modifier: Modifier = Modifier) {
    val now by produceState(initialValue = LocalDateTime.now(), key1 = Unit) {
        while (true) {
            value = LocalDateTime.now()
            delay(20_000)
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = rememberSystem24Hour()
    val weekday = remember(now.dayOfWeek, locale) {
        now.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
    }
    val date = remember(now.toLocalDate(), locale) { sceneDate(now, locale) }
    val hour = remember(now, is24Hour) { sceneHourText(now.toLocalTime(), is24Hour) }
    val time = remember(now, is24Hour) { sceneTimeText(now.toLocalTime(), is24Hour) }
    val meridiem = remember(now, is24Hour, locale) {
        sceneMeridiemText(now.toLocalTime(), is24Hour, locale)
    }
    // 12 小时制多一件事要说，而这条竖排很窄，塞不进数字那一行；
    // 于是它跟日期并排 —— 四种样子里有三种本来就在数字下面画日期。
    val dateLine = if (meridiem == null) date else meridiem + " " + date

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (style) {
            lo.naui.ui.theme.ClockStyle.Stacked -> {
                if (meridiem != null) {
                    SceneClockLine(meridiem, 11.sp, alpha = 0.82f)
                    Spacer(Modifier.height(4.dp))
                }
                SceneClockLine(hour, 30.sp)
                Box(
                    Modifier
                        .padding(vertical = 6.dp)
                        .width(16.dp)
                        .height(1.dp)
                        .background(SceneOnWallpaper.copy(alpha = 0.42f))
                )
                SceneClockLine(now.format(ClockFormatterMinute), 30.sp)
            }

            lo.naui.ui.theme.ClockStyle.Inline -> {
                SceneClockLine(time, 22.sp)
                Spacer(Modifier.height(4.dp))
                SceneClockLine(dateLine, 11.sp, alpha = 0.82f)
            }

            lo.naui.ui.theme.ClockStyle.Analog -> {
                SceneClockDial(now.toLocalTime())
                Spacer(Modifier.height(6.dp))
                SceneClockLine(dateLine, 11.sp, alpha = 0.82f)
            }

            lo.naui.ui.theme.ClockStyle.Date -> {
                SceneClockLine(weekday, 20.sp)
                Spacer(Modifier.height(2.dp))
                SceneClockLine(date, 11.sp, alpha = 0.82f)
            }
        }
    }
}

/**
 * 时钟的一行。它占满整条宽度，好让长日期被截断而不是画到别人身上，
 * 也因此是居中的。
 */
@Composable
private fun SceneClockLine(text: String, fontSize: TextUnit, alpha: Float = 0.96f) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            color = SceneOnWallpaper.copy(alpha = alpha),
            shadow = SceneTextShadow,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 一块表盘：一圈淡淡的环、四个刻度、两根指针。
 * 环和刻度都压得很轻，这样真正用来读时间的指针才是整块表上唯一实的东西。
 */
@Composable
private fun SceneClockDial(time: LocalTime) {
    Canvas(Modifier.size(46.dp)) {
        val ring = 1.4.dp.toPx()
        val radius = size.minDimension / 2f - ring
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = SceneOnWallpaper.copy(alpha = 0.5f),
            radius = radius,
            center = center,
            style = Stroke(width = ring),
        )
        repeat(4) { index ->
            val angle = Math.toRadians((index * 90f - 90f).toDouble())
            drawCircle(
                color = SceneOnWallpaper.copy(alpha = 0.55f),
                radius = 1.dp.toPx(),
                center = center + Offset(
                    (cos(angle) * (radius - 5.dp.toPx())).toFloat(),
                    (sin(angle) * (radius - 5.dp.toPx())).toFloat(),
                ),
            )
        }

        // 时针跟着分钟慢慢挪，这样表盘不会看起来停住了。
        val minuteAngle = Math.toRadians((time.minute * 6f - 90f).toDouble())
        val hourAngle = Math.toRadians(
            ((time.hour % 12) * 30f + time.minute * 0.5f - 90f).toDouble()
        )
        drawLine(
            color = SceneOnWallpaper.copy(alpha = 0.94f),
            start = center,
            end = center + Offset(
                (cos(hourAngle) * radius * 0.5f).toFloat(),
                (sin(hourAngle) * radius * 0.5f).toFloat(),
            ),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = SceneOnWallpaper.copy(alpha = 0.94f),
            start = center,
            end = center + Offset(
                (cos(minuteAngle) * radius * 0.78f).toFloat(),
                (sin(minuteAngle) * radius * 0.78f).toFloat(),
            ),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** 月和日，按手机自己的语言写 */
private fun sceneDate(now: LocalDateTime, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd") ?: "MMM d"
    return now.format(DateTimeFormatter.ofPattern(pattern, locale))
}
