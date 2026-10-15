package lo.naui.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 小方框里放什么 */
enum class PreviewKind { Text, Image, Video, App }

/** 一张预览要的两样东西：画什么 + 拿不到时的文字 */
data class FileVisual(
    val kind: PreviewKind,
    val label: String,
    val image: ImageBitmap? = null,
)

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")
private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "ts", "m4v")

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** 小方框里显示的那几个字：sh / txt / ttf / zip … */
fun typeLabel(name: String): String {
    val e = extOf(name)
    return if (e.isBlank()) "·" else e.take(4)
}

fun kindOf(name: String): PreviewKind = when (extOf(name)) {
    in IMAGE_EXT -> PreviewKind.Image
    in VIDEO_EXT -> PreviewKind.Video
    "apk" -> PreviewKind.App
    else -> PreviewKind.Text
}

/**
 * 异步把缩略图 / 应用图标捞出来。
 *
 * 全部丢到 IO 上做，而且**只在小方框那块用得上**；
 * 拿不到就返回 null，界面上显示文字类型，不阻塞列表。
 */
@Composable
fun rememberVisual(ctx: Context, path: String, name: String, isDir: Boolean): FileVisual {
    val kind = kindOf(name)
    val label = typeLabel(name)
    val state = produceState(
        initialValue = FileVisual(kind, label),
        key1 = path,
    ) {
        if (isDir) {
            value = FileVisual(kind, label)
            return@produceState
        }
        val img = withContext(Dispatchers.IO) {
            runCatching {
                when (kind) {
                    PreviewKind.Image -> decodeImage(path)
                    PreviewKind.Video -> videoFrame(path)
                    PreviewKind.App -> appIcon(ctx, path)
                    PreviewKind.Text -> null
                }
            }.getOrNull()
        }
        value = FileVisual(kind, label, img)
    }
    return state.value
}

private const val THUMB_PX = 160

private fun decodeImage(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var scale = 1
    while (bounds.outWidth / scale > THUMB_PX * 2) scale *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = scale }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}

private fun videoFrame(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val frame: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX, THUMB_PX)
        } else {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        frame?.asImageBitmap()
    } catch (e: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** apk 就把它自己的图标抠出来 */
private fun appIcon(ctx: Context, path: String): ImageBitmap? {
    val pm = ctx.packageManager
    val info = pm.getPackageArchiveInfo(path, 0) ?: return null
    info.applicationInfo?.let { ai ->
        ai.sourceDir = path
        ai.publicSourceDir = path
        // 低版本用旧的 drawable 字段，新版本用 getApplicationIcon
        val d = runCatching { if (Build.VERSION.SDK_INT >= 26) ai.loadIcon(pm) else ai.loadIcon(pm) }
            .getOrNull() ?: return null
        return drawableToBitmap(d, THUMB_PX)?.asImageBitmap()
    }
    return null
}

private fun drawableToBitmap(d: android.graphics.drawable.Drawable, size: Int): Bitmap? = runCatching {
    if (d is android.graphics.drawable.BitmapDrawable && d.bitmap != null) {
        Bitmap.createScaledBitmap(d.bitmap, size, size, true)
    } else {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        bmp
    }
}.getOrNull()

/** 顺手拿一下图片/视频的像素尺寸，属性窗口用得上（暂时没用上，留着） */
fun imageSizeOf(path: String): Size? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth > 0) Size(o.outWidth, o.outHeight) else null
}.getOrNull()
