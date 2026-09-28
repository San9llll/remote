package lo.naui.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 场景图比卡片高出的一截，用来做视差容错（对齐 Aster 的 SceneParallaxOverscan） */
private const val SceneParallaxOverscan = 1.12f

/** 滚动时壁纸相对页面移动多少倍 */
private const val SceneParallaxRate = 0.28f

/**
 * 场景主卡：壁纸 + 底部渐隐 + 状态字。
 *
 * 结构和 Aster 的 SceneHero 一样 —— 壁纸、把照片淡进面板色的竖向渐变、
 * 随滚动加深的顶部洗色，最后才是站在上面的状态文字。
 */
@Composable
fun SceneHero(
    wallpaper: ImageBitmap?,
    title: String,
    subtitle: String,
    badge: String?,
    scrollState: ScrollState,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    scrollsWithPage: Boolean = true,
    topContent: @Composable () -> Unit = {},
    /** 右下角那块（放重启圈这种） */
    bottomEndContent: @Composable () -> Unit = {},
) {
    Box(
        Modifier
            .then(modifier)
            .clip(shape),
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(SceneParallaxOverscan)
                    .graphicsLayer {
                        translationY = (if (scrollsWithPage) scrollState.value * SceneParallaxRate else 0f)
                            .coerceAtMost(size.height * SceneParallaxRate / SceneParallaxOverscan)
                        // 离屏合成，下面那个 alpha 遮罩才生效
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    // 底部**直接淡出成透明**，露出下层画面 —— 不是盖一层灰色
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                0.62f to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MiuixTheme.colorScheme.primaryContainer,
                                MiuixTheme.colorScheme.secondaryContainer,
                            )
                        )
                    )
            )
        }


        // 卡片往上滑的时候会露出被切开的照片边，所以按滚动比例给它自己的顶部洗一层面板色，
        // 让它化开而不是被切断。静止时这层全是透明的。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (scrollsWithPage) {
                        (scrollState.value / size.height).coerceIn(0f, 1f) * 0.95f
                    } else {
                        0f
                    }
                }
                .background(
                    Brush.verticalGradient(
                        0f to MiuixTheme.colorScheme.surface,
                        0.42f to Color.Transparent,
                    )
                )
        )

        // 站在照片上的顶部内容（场景时钟）
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 18.dp),
        ) {
            topContent()

        // 右下角：叠在大图上（空的时候不占地方）
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp),
        ) { bottomEndContent() }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title3,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (badge != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = badge,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 问候：上面一行小的（问候语 · 环境），下面一行大的（今天的句子）。
 * 对齐 Aster 的 SceneGreeting（footnote1 + 8dp + title3 + 18dp）。
 */
@Composable
fun SceneGreeting(
    greetingLine: String,
    quote: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = greetingLine,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = quote,
            style = MiuixTheme.textStyles.title3,
        )
        Spacer(Modifier.height(18.dp))
    }
}
