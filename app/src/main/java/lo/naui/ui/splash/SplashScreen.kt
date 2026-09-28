package lo.naui.ui.splash

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开屏。
 *
 * 视觉上做了三件事：
 *  1. 背景是**两层渐变**（主色斜向 + 底部深色），不是一块死板的白/黑
 *  2. 中间那个「N」带**呼吸动画**（缓慢缩放 + 透明度起伏）
 *  3. 进度用**环形**走，比进度条精致
 *
 * 本地版不连服务器，所以这里只是把界面自己热起来。
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var step by remember { mutableStateOf("正在准备…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var skipped by remember { mutableStateOf(false) }

    val primary = MiuixTheme.colorScheme.primary
    val surface = MiuixTheme.colorScheme.surface

    // 呼吸：0.97 ~ 1.03 来回
    val breathe = rememberInfiniteTransition(label = "breathe")
    val scale by breathe.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val glow by breathe.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    LaunchedEffect(Unit) {
        val steps = listOf("准备界面", "装载主题", "就绪")
        steps.forEachIndexed { i, label ->
            if (skipped) return@LaunchedEffect
            step = label
            progress = (i + 1).toFloat() / steps.size
            withFrameNanos { }
            delay(140)
        }
        delay(180)
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.16f),
                        surface,
                        MiuixTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 中间那个字：带呼吸 ----
            Box(
                Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 背后一圈柔光
                Box(
                    Modifier
                        .size(140.dp)
                        .alpha(glow)
                        .background(
                            Brush.radialGradient(
                                listOf(primary.copy(alpha = 0.22f), Color.Transparent)
                            ),
                            RoundedCornerShape(70.dp),
                        )
                )
                Text(
                    "N",
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Nakour",
                fontSize = 12.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(52.dp))

            // ---- 环形进度 ----
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 5.dp.toPx()
                    val inset = stroke / 2
                    // 底环
                    drawArc(
                        color = primary.copy(alpha = 0.18f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke
                        ),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    // 进度
                    drawArc(
                        color = primary,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke
                        ),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Text(
                    (progress * 100).toInt().toString(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                step,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        // ---- 右下角：跳过 ----
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(26.dp)
                .clip(RoundedCornerShape(50))
                .background(primary.copy(alpha = 0.12f))
                .clickable {
                    skipped = true
                    onDone()
                }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("跳过", fontSize = 13.sp, color = primary)
        }
    }
}
