package lo.naui.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 切背景时从点击点扩散的那些"涟漪"。
 *
 * ## 是什么
 *
 * 点「1 / 2 / 自定义」换背景的时候，新背景不是直接啪一下换掉，
 * 而是**从你点的那个位置一圈圈扩出来**，扩过的地方才是新背景，
 * 没扩到的地方还是旧背景。扩的那条边用一圈液态玻璃的光。
 *
 * ## 为什么要"叠加"
 *
 * 用户要求 1 秒内连点多次时效果要叠着来，而不是后一个把前一个打断。
 * 所以这里存的是一个**列表** —— 每个涟漪自己算自己的进度，
 * 渲染时把所有圆的**并集**裁出来就行。
 *
 * 每个涟漪 800ms 走完，跑完的自动清掉。
 */
object BgRipples {

    /** 一个涟漪：从哪儿长出来的 + 什么时候开始的 */
    data class Ripple(
        /** 归一化的圆心（0~1），跟屏幕尺寸无关 */
        val cx: Float,
        val cy: Float,
        val at: Long,
    )

    /** 一趟跑完要多久 */
    const val DURATION_MS = 800L

    /** 跑完之后还留一会儿（切线那圈光要淡出） */
    private const val LINGER_MS = 1100L

    var list by mutableStateOf<List<Ripple>>(emptyList())
        private set

    /**
     * 记一个"想从这儿扩散"的位置，但**先不播**。
     *
     * 为什么分两步：新图要从 assets 解码（一张一百多 KB 的 PNG），
     * 而点击那一瞬间图还没好。如果立即开跑，动画跑完了图才出来 ——
     * 实机上看起来就是"先啪一下整张图占满屏幕，动画才跟上"。
     *
     * 所以：点击只登记，等图真加载完了再 [fire]。
     */
    private var pending: Pair<Float, Float>? = null

    fun pend(x: Float, y: Float) {
        pending = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    /** 图就绪了，开始扩散 */
    fun fire() {
        val p = pending ?: return
        pending = null

        val now = System.currentTimeMillis()
        // 先清掉已经跑完的，免得列表越堆越长
        val alive = list.filter { now - it.at < LINGER_MS }
        list = alive + Ripple(p.first, p.second, now)
    }

    /**
     * 从某个点起一圈（立即）。
     *
     * [x] / [y] 是**归一化**的屏幕坐标（0~1）—— 这样不用管
     * 传进来的是 px 还是 dp，也不怕窗口尺寸变。
     */
    fun add(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val alive = list.filter { now - it.at < LINGER_MS }
        list = alive + Ripple(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), now)
    }

    /** 现在还在跑的（给渲染用） */
    fun active(now: Long = System.currentTimeMillis()): List<Ripple> =
        list.filter { now - it.at < LINGER_MS }

    fun clear() {
        list = emptyList()
    }

    /**
     * 某个涟漪走到哪儿了（0~1）。
     *
     * 超过 [DURATION_MS] 就当 1 —— 渲染时按这个决定圆多大。
     */
    fun progressOf(r: Ripple, now: Long = System.currentTimeMillis()): Float =
        ((now - r.at).toFloat() / DURATION_MS).coerceIn(0f, 1f)

    /**
     * 切线那圈光的强度（1 → 0）。
     *
     * 扩散的时候亮，扩完了慢慢淡掉，不是啪一下没。
     */
    fun edgeAlphaOf(r: Ripple, now: Long = System.currentTimeMillis()): Float {
        val t = ((now - r.at).toFloat() / LINGER_MS).coerceIn(0f, 1f)
        return (1f - t) * 0.9f
    }
}
