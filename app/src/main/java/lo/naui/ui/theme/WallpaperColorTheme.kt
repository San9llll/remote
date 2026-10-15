// 取色思路参考 Aster 的 WallpaperColorTheme（GPL-3.0），实现换成不依赖额外库的量化取色
package lo.naui.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 壁纸里那个可以用来染色的种子色。
 *
 * Aster 里这个来源优先级最高：它比系统调色板更具体，因为那是用户自己挑的照片。
 * 只有在全景首页（有壁纸可看的那种）它才算数 —— 一张没在屏幕上的图不该在背后给全 App 上色。
 */
@Immutable
data class WallpaperColorState(
    val enabled: Boolean,
    val seed: Int,
    val backdropIsLight: Boolean,
)

object WallpaperColorTheme {
    const val EnabledKey = "wallpaper_color_enabled"
    const val SeedKey = "wallpaper_color_seed"
    const val LightKey = "wallpaper_color_is_light"

    var enabled by mutableStateOf(false)
        private set
    var seed by mutableStateOf(0)
        private set
    var backdropIsLight by mutableStateOf(false)
        private set

    fun load(prefs: android.content.SharedPreferences) {
        enabled = prefs.getBoolean(EnabledKey, false)
        seed = prefs.getInt(SeedKey, 0)
        backdropIsLight = prefs.getBoolean(LightKey, false)
    }

    fun setEnabled(prefs: android.content.SharedPreferences, value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(EnabledKey, value).apply()
    }

    fun setSeed(prefs: android.content.SharedPreferences, value: Int, isLight: Boolean) {
        seed = value
        backdropIsLight = isLight
        prefs.edit()
            .putInt(SeedKey, value)
            .putBoolean(LightKey, isLight)
            .apply()
    }

    fun clearSeed(prefs: android.content.SharedPreferences) {
        seed = 0
        backdropIsLight = false
        prefs.edit().remove(SeedKey).remove(LightKey).apply()
    }
}

/**
 * 从一张图里取一个能当种子用的颜色。
 *
 * 做法是把图缩到 32×32，把每个像素量化到每通道 4 位（也就是 4096 个格子），
 * 数哪个格子出现得最多；同票数时选更饱和的那个，因为灰扑扑的墙和天空
 * 虽然面积大，染出来却什么都看不出来。
 *
 * 顺带回报这张图整体是亮是暗 —— 场景上的图标要靠它决定用白还是用黑。
 */
fun dominantSeed(bitmap: Bitmap): Pair<Int, Boolean> {
    val size = 32
    val small = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val bins = HashMap<Int, IntArray>()
    var lightPixels = 0

    for (y in 0 until size) {
        for (x in 0 until size) {
            val c = small.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            if (luma > 180) lightPixels++
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val acc = bins.getOrPut(key) { IntArray(4) } // r, g, b, 计数
            acc[0] += r; acc[1] += g; acc[2] += b; acc[3] += 1
        }
    }
    if (small != bitmap) small.recycle()

    var bestKey = 0
    var bestScore = -1.0
    var bestAvg = 0
    bins.forEach { (key, acc) ->
        val n = acc[3]
        val r = acc[0] / n
        val g = acc[1] / n
        val b = acc[2] / n
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sat = if (max == 0) 0f else (max - min).toFloat() / max
        // 出现次数为主，饱和度为辅：面积大的颜色优先，但不能灰得毫无颜色
        val score = n * (0.35 + sat)
        if (score > bestScore) {
            bestScore = score
            bestKey = key
            bestAvg = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    if (bestScore < 0) return 0xFF2196F3.toInt() to false
    return bestAvg to (lightPixels > size * size / 2)
}
