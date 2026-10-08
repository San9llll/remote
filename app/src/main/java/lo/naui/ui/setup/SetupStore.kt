package lo.naui.ui.setup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "第一次进入"的那些标记。
 *
 * ## 为什么要单独管
 *
 * 用途是"在已经有使用数据的情况下，把那些只有第一次进来才会出现的界面
 * 再触发一遍"。入口现在是「设置 → 关于 → 连点版本号 5 次」
 *（原来那个「开发者工具」分区已经整个删掉了）。
 *
 * 那就得有个地方**记住"哪些第一次已经做过了"** —— 才能被"重置"。
 *
 * 这里就干这个：一堆布尔标记 + 一个总开关。
 */
object SetupStore {

    private var sp: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.getSharedPreferences("nakour_setup", Context.MODE_PRIVATE)
        done = sp?.getBoolean("done", false) ?: false
        lastStep = sp?.getInt("last_step", 0) ?: 0
    }

    /** 走完准备界面了吗 */
    var done by mutableStateOf(false)
        private set

    /** 上次停在第几步（中途退出了可以接着走） */
    var lastStep by mutableStateOf(0)
        private set

    /** 第一步的总数（改流程的时候同步改它） */
    const val TOTAL_STEPS = 5

    fun finish() {
        done = true
        lastStep = TOTAL_STEPS
        sp?.edit()?.putBoolean("done", true)?.putInt("last_step", TOTAL_STEPS)?.apply()
    }

    fun saveStep(i: Int) {
        lastStep = i
        sp?.edit()?.putInt("last_step", i)?.apply()
    }

    /**
     * 重置成"没走过"。
     *
     * 「关于」页连点版本号 5 次那个后门调的就是它。
     */
    fun reset() {
        done = false
        lastStep = 0
        sp?.edit()?.putBoolean("done", false)?.putInt("last_step", 0)?.apply()
    }

    /** 现在该显示准备界面吗 */
    val shouldShow: Boolean get() = !done
}
