package lo.naui.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 切背景时从点击点扩散的那些"涟漪"。
 *
 * ## 是什么
 *
 * 点「1 / 2 / 自定义」换背景的时候，新背景不是直接啪一下换掉，
 * 而是**从你点的那个位置一圈圈扩出来**，扩过的地方才是新背景，
 * 没扩到的地方还是旧背景。
 *
 * ## 为什么是"队列"而不是"一个"
 *
 * 1.00.0 之前这里只有**一个** pending 槽位 + 外壳里一对
 * shownWallpaper / pendingWallpaper。后果是：一秒内连点三次，
 * 后两次直接把前一次顶掉 —— **只有最后一次会播完**，中间那张图
 * 用户压根看不见（用户原话："多次切换时没有做到上次渲染继续完成"）。
 *
 * 现在每条涟漪**自带它要推出来的那张图**，按时间顺序排队叠着画：
 * 每个圆各画各的图，后一个圆扩过来的时候才盖住前一个 ——
 * 所以每一次切换都会跑完自己那 800ms。
 *
 * ## 为什么要有上限
 *
 * 每条涟漪 = 一层全屏绘制。连点十次堆十层，手机上必卡
 * （用户原话："短时间多次切换卡顿"）。所以队列封顶 [MAX_QUEUE] 条，
 * 超了就掐中间：最早那条和最新那条一定保留（头 = 你从哪儿来的，
 * 尾 = 你最终要去哪儿），中间的合并掉。
 */
object BgRipples {

    /** 一个涟漪：从哪儿长出来的 + 什么时候开始的 + 要把哪张图推出来 */
    data class Ripple(
        /** 归一化的圆心（0~1），跟屏幕尺寸无关 */
        val cx: Float,
        val cy: Float,
        val at: Long,
        /** 这一趟要推出来的新图 —— 每条自带一张，连点才不会互相顶掉 */
        val to: ImageBitmap,
    )

    /** 一趟跑完要多久 */
    const val DURATION_MS = 800L

    /** 跑完之后还留一会儿，免得边界那圈光啪一下没 */
    private const val LINGER_MS = 1100L

    /** 队列上限，超了掐中间 */
    private const val MAX_QUEUE = 3

    var list by mutableStateOf<List<Ripple>>(emptyList())
        private set

    /**
     * 队列全跑完之后，背景该扶正成哪张。
     *
     * 外壳靠它把 shownWallpaper 换成最新那张 —— 换早了过渡就白做了，
     * 换晚了涟漪播完底下还是旧图。
     */
    var settledTo by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 现在有没有在跑（外壳用它决定什么时候扶正背景） */
    val running: Boolean get() = list.isNotEmpty()

    /**
     * 记一个"想从这儿扩散"的位置，但**先不播**。
     *
     * 为什么分两步：新图要从 assets 解码（一张一百多 KB 的 PNG），
     * 而点击那一瞬间图还没好。如果立即开跑，动画跑完了图才出来 ——
     * 实机上看起来就是"先啪一下整张图占满屏幕，动画才跟上"。
     *
     * 所以：点击只登记，等图真加载完了再 [fire]。
     *
     * ⚠️ 这个槽位**是**会被后一次点击覆盖的，但这不影响"每次都播完" ——
     * 覆盖掉的只是"还没配上图的那次点击的圆心"，图一到就各自 fire 一条进队列。
     */
    private var pending: Pair<Float, Float>? = null

    fun pend(x: Float, y: Float) {
        pending = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    /** 图就绪了，把它排进队列开始扩散 */
    fun fire(to: ImageBitmap) {
        val p = pending ?: return
        pending = null

        val now = System.currentTimeMillis()
        // 先清掉已经跑完的，免得列表越堆越长
        val alive = list.filter { now - it.at < LINGER_MS }
        val next = alive + Ripple(p.first, p.second, now, to)
        list = if (next.size > MAX_QUEUE) squash(next) else next
        settledTo = to
    }

    /** 掐中间：留头、留尾、中间匀一条 */
    private fun squash(next: List<Ripple>): List<Ripple> =
        listOf(next.first(), next[next.size / 2], next.last())

    /** 现在还在跑的（给渲染用） */
    fun active(now: Long = System.currentTimeMillis()): List<Ripple> =
        list.filter { now - it.at < LINGER_MS }

    /**
     * 把跑完的从队列里摘掉。渲染循环每帧调一次。
     *
     * ⚠️ 这一步**不能省**：1.00.0 之前没人负责把"涟漪结束"这件事
     * 通知回外壳，外壳里那个 ripplesActive 是组合期算的一次性值，
     * 永远停在 true，卡片玻璃就再也没恢复过。
     * 现在摘空的那一刻 list 变了 → 订阅它的外壳重组 → 背景扶正、玻璃恢复。
     */
    fun prune(now: Long = System.currentTimeMillis()) {
        if (list.isEmpty()) return
        val alive = list.filter { now - it.at < LINGER_MS }
        if (alive.size != list.size) list = alive
    }

    /**
     * 分界框该有多亮（1 → 0）。
     *
     * 1.00.0 曾经当死代码删过它，后来用户要求"分界线改半透明框"，
     * 那道框就是要跟着涟漪的命（[DURATION_MS] 跑完 + 一点 linger）淡出去 ——
     * 于是又加回来了。淡出用 LINGER 而不是 DURATION：圈扩到头之后还要留一会儿。
     */
    fun edgeAlphaOf(r: Ripple, now: Long = System.currentTimeMillis()): Float {
        val t = ((now - r.at).toFloat() / LINGER_MS).coerceIn(0f, 1f)
        return (1f - t) * 0.9f
    }

    fun clear() {
        list = emptyList()
        settledTo = null
        pending = null
    }

    /**
     * 某个涟漪走到哪儿了（0~1）。
     *
     * 超过 [DURATION_MS] 就当 1 —— 渲染时按这个决定圆多大。
     */
    fun progressOf(r: Ripple, now: Long = System.currentTimeMillis()): Float =
        ((now - r.at).toFloat() / DURATION_MS).coerceIn(0f, 1f)
}
