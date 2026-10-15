package lo.naui.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 内置背景风格。
 *
 * 用户给了两套自己挑的图，打包在 assets 里：
 *
 * | 样式 | 目录 | 大图 | 权限弹窗背景 |
 * |---|---|---|---|
 * | **1**（nk） | `assets/bg/nk` | 15 张 | 没有 |
 * | **2**（gc） | `assets/bg/gc` | 20 张 | 3 张 |
 * | **自定义** | 用户自己选 | — | — |
 *
 * 每次启动从对应目录里**随机挑一张**，主页上滑可以换下一张。
 * 权限弹窗那张也是随机挑（只有 gc 有）。
 */
enum class BgStyle(val id: String, val label: String) {
    Nk("nk", "1"),
    Gc("gc", "2"),
    Custom("custom", "自定义"),
    ;

    companion object {
        fun of(id: String?): BgStyle = entries.firstOrNull { it.id == id } ?: Nk
    }
}

object BuiltinBg {

    /** assets 里每套图的目录 */
    private fun heroDir(style: BgStyle): String = when (style) {
        BgStyle.Nk -> "bg/nk"
        BgStyle.Gc -> "bg/gc"
        BgStyle.Custom -> ""          // 自定义走用户选的，不走这儿
    }

    private fun dialogDir(style: BgStyle): String = when (style) {
        BgStyle.Nk -> "bg/nk_dialog"      // 这套没有，会是空的
        BgStyle.Gc -> "bg/gc_dialog"
        BgStyle.Custom -> ""
    }

    /** 列一个目录下的 png（带缓存，assets 列表不会变） */
    private val cache = HashMap<String, List<String>>()

    private fun list(ctx: Context, dir: String): List<String> {
        if (dir.isBlank()) return emptyList()
        cache[dir]?.let { return it }
        val out = runCatching {
            ctx.assets.list(dir)?.filter { it.endsWith(".png", true) }?.sorted().orEmpty()
        }.getOrDefault(emptyList())
        cache[dir] = out
        return out
    }

    /** 某套风格的大图路径（assets 相对路径） */
    fun heroAssets(ctx: Context, style: BgStyle): List<String> {
        val dir = heroDir(style)
        if (dir.isBlank()) return emptyList()
        // ⚠️ 必须写成 ${dir} —— 裸的 "$dir" 也行，但 "$heroDir(style)" 是**不会调用函数**的，
        // Kotlin 只把它当变量名，编译期直接报 "Function invocation expected"
        return list(ctx, dir).map { "$dir/$it" }
    }

    /** 某套风格的权限弹窗背景（可能为空） */
    fun dialogAssets(ctx: Context, style: BgStyle): List<String> {
        val dir = dialogDir(style)
        if (dir.isBlank()) return emptyList()
        return list(ctx, dir).map { "$dir/$it" }
    }

    /**
     * 内容页背景。
     *
     * 压缩包里每个样式一张（`bg/page/nk`、`bg/page/gc`）。
     * 这是"功能 / 概览 / 设置"那几页铺的底。
     */
    fun pageAsset(ctx: Context, style: BgStyle): String? {
        if (style == BgStyle.Custom) return null
        val dir = "bg/page/" + style.id
        return list(ctx, dir).firstOrNull()?.let { "$dir/$it" }
    }

    /**
     * 设置页背景。
     *
     * 压缩包根目录给了两张，按样式名分（`bg/settings/nk.png` / `gc.png`）。
     * 设置页和它所有子页都用它。
     */
    fun settingsAsset(style: BgStyle): String? = when (style) {
        BgStyle.Nk -> "bg/settings/nk.png"
        BgStyle.Gc -> "bg/settings/gc.png"
        BgStyle.Custom -> null
    }

    /** 随机挑一张大图 */
    fun randomHero(ctx: Context, style: BgStyle): String? =
        heroAssets(ctx, style).randomOrNull()

    /** 随机挑一张权限弹窗背景 */
    fun randomDialog(ctx: Context, style: BgStyle): String? =
        dialogAssets(ctx, style).randomOrNull()

    /**
     * 已解码的图缓存。
     *
     * 为什么要有：一张一百多 KB 的 PNG 解出来是好几 MB，每次切都现解
     * 会明显顿一下（用户反馈"切换时太卡"）。
     * 而且这些图是**打包死的**，内容永远不变，缓存起来完全安全。
     */
    private val bitmapCache = HashMap<String, Bitmap>()

    /** 从 assets 读一张图（带缓存） */
    fun load(ctx: Context, assetPath: String?): Bitmap? {
        if (assetPath.isNullOrBlank()) return null
        bitmapCache[assetPath]?.let { return it }
        val bmp = runCatching {
            ctx.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bmp != null) bitmapCache[assetPath] = bmp
        return bmp
    }

    /**
     * 预热。
     *
     * 用户要求"打开应用时就将这些准备就绪" —— 所以启动时把当前样式的
     * 那几张（大图 / 内容页 / 设置页 / 权限弹窗）先解好放缓存里，
     * 之后切换就是直接拿，没有解码那一下。
     *
     * 只热当前样式：1 / 2 两套全热的话要占十几 MB 内存，不值当。
     */
    fun preload(ctx: Context, style: BgStyle) {
        if (style == BgStyle.Custom) return
        runCatching {
            // 大图先来一张（这张是界面上最显眼的）
            randomHero(ctx, style)?.let { load(ctx, it) }
            // 内容页 / 设置页
            pageAsset(ctx, style)?.let { load(ctx, it) }
            settingsAsset(style)?.let { load(ctx, it) }
            // 权限弹窗的背景（只有样式 2 有）
            randomDialog(ctx, style)?.let { load(ctx, it) }
        }
    }

    /** 清缓存（换样式的时候把不要的丢掉，省内存） */
    fun trim(keepStyle: BgStyle, ctx: Context) {
        runCatching {
            val keepPrefixes = listOf(
                "bg/" + keepStyle.id + "/",
                "bg/page/" + keepStyle.id + "/",
                "bg/" + keepStyle.id + "_dialog/",
                "bg/settings/" + keepStyle.id,
                "bg/btn_" + keepStyle.id,
            )
            val dead = bitmapCache.keys.filterNot { k -> keepPrefixes.any { k.startsWith(it) } }
            dead.forEach { bitmapCache.remove(it) }
        }
    }

    /**
     * 当前该用哪张。
     *
     * 自定义 → 用用户自己设的（[ThemePrefs.currentHero]）
     * 1 / 2  → 从内置里随机（每次启动或者上滑换）
     */
    fun resolve(ctx: Context, prefs: ThemePrefs): String? = when (prefs.bgStyle) {
        BgStyle.Custom -> prefs.currentHero.takeIf { it.isNotBlank() }
        else -> prefs.builtinHero.takeIf { it.isNotBlank() }
            ?: randomHero(ctx, prefs.bgStyle)?.also { prefs.updateBuiltinHero(it) }
    }
}
