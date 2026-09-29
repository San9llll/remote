package lo.naui.sys

import android.view.KeyEvent

/**
 * 音量上 + 音量下「同时按」。
 *
 * 为什么不写在 Activity 的 onKeyDown 里直接回调：
 * 需要它的地方是 Compose 的树（外壳要按当前页面决定加还是删快捷方式），
 * 所以这里只做「判定」，判定完广播出去，谁关心谁注册。
 *
 * 两个键都处于按下状态就算触发；触发后 1.2 秒内不再触发（防按住重复）。
 */
object VolumeChordBus {

    private const val DEBOUNCE_MS = 1200L

    private val listeners = mutableSetOf<() -> Unit>()
    private val held = mutableSetOf<Int>()

    @Volatile private var lastFireAt = 0L

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /** 现在按着的第一个音量键（没有就是 0） */
    fun firstKeyHeld(): Int = held.firstOrNull() ?: 0

    /** 返回 true = 这一下被吃掉了，不要再去调音量 */
    fun onKeyDown(keyCode: Int): Boolean {
        if (!isVolumeKey(keyCode)) return false
        held.add(keyCode)
        if (held.size < 2) return false
        held.clear()
        val now = System.currentTimeMillis()
        if (now - lastFireAt < DEBOUNCE_MS) return true
        lastFireAt = now
        listeners.toList().forEach { runCatching { it() } }
        return true
    }

    fun onKeyUp(keyCode: Int) {
        held.remove(keyCode)
    }

    fun clear() {
        held.clear()
    }
}
