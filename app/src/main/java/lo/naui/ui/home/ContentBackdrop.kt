package lo.naui.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内容页背景 —— 铺在模块 / 概览 / 设置这些页面下的一层。
 *
 * 用一张本地图（主题页里自选）模糊后 + 半透明面板盖一层，
 * 保证上面的字读得清。**主页和侧边栏不受它影响。**
 */
@Composable
fun ContentBackdrop(path: String, modifier: Modifier = Modifier) {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        bmp = if (path.isBlank()) {
            null
        } else {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val image = bmp ?: return

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(24.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
        )
        // 压一层面板色，保证前景可读
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.74f))
        )
    }
}
