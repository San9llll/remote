package lo.naui.term

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 终端自己的设置（跟主题分开存）。
 *
 * 这几项改完是**当场生效**的：配色、字号、功能键行、几个开关。
 */
object TerminalSettings {

    private var sp: android.content.SharedPreferences? = null

    /** 配色 id */
    var schemeId by mutableStateOf("nakour")
        private set

    val scheme: ColorScheme get() = ColorSchemes.of(schemeId)

    /** 字号（sp） */
    var fontSize by mutableStateOf(12.5f)
        private set

    /** 功能键行：2 = 两行，1 = 一行，0 = 不显示 */
    var extraKeysRows by mutableStateOf(2)
        private set

    /** 长按音量键当 Ctrl */
    var volumeKeysAsCtrl by mutableStateOf(true)
        private set

    /** 终端跑着的时候别锁屏 */
    var keepScreenOn by mutableStateOf(true)
        private set

    /** 命令跑完震一下 */
    var bellVibrate by mutableStateOf(true)
        private set

    /** 终端里点链接直接在浏览器打开 */
    var openUrls by mutableStateOf(true)
        private set

    /** 回看历史最多留多少行 */
    var scrollbackLines by mutableStateOf(4000)
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_term", Context.MODE_PRIVATE)
        sp = p
        schemeId = p.getString("scheme", "nakour") ?: "nakour"
        fontSize = p.getFloat("font_size", 12.5f)
        extraKeysRows = p.getInt("extra_rows", 2)
        volumeKeysAsCtrl = p.getBoolean("vol_ctrl", true)
        keepScreenOn = p.getBoolean("keep_on", true)
        bellVibrate = p.getBoolean("bell_vibrate", true)
        openUrls = p.getBoolean("open_urls", true)
        scrollbackLines = p.getInt("scrollback", 4000)
    }

    fun updateScheme(id: String) {
        schemeId = id
        sp?.edit()?.putString("scheme", id)?.apply()
    }

    fun updateFontSize(v: Float) {
        fontSize = v.coerceIn(6f, 32f)
        sp?.edit()?.putFloat("font_size", fontSize)?.apply()
    }

    fun updateExtraKeysRows(v: Int) {
        extraKeysRows = v.coerceIn(0, 3)
        sp?.edit()?.putInt("extra_rows", extraKeysRows)?.apply()
    }

    fun updateVolumeKeysAsCtrl(v: Boolean) {
        volumeKeysAsCtrl = v
        sp?.edit()?.putBoolean("vol_ctrl", v)?.apply()
    }

    fun updateKeepScreenOn(v: Boolean) {
        keepScreenOn = v
        sp?.edit()?.putBoolean("keep_on", v)?.apply()
    }

    fun updateBellVibrate(v: Boolean) {
        bellVibrate = v
        sp?.edit()?.putBoolean("bell_vibrate", v)?.apply()
    }

    fun updateOpenUrls(v: Boolean) {
        openUrls = v
        sp?.edit()?.putBoolean("open_urls", v)?.apply()
    }

    fun updateScrollback(v: Int) {
        scrollbackLines = v.coerceIn(500, 20000)
        sp?.edit()?.putInt("scrollback", scrollbackLines)?.apply()
    }
}
