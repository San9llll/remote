package lo.naui.sys

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面上的"上次我在哪儿"。
 *
 * 用户要求：侧栏那个功能快捷按钮，点进去要**接着上次的地方**，
 * 而不是每次都回到那个页的主页。所以这些状态得活过页面的销毁。
 */
object UiState {

    private var sp: android.content.SharedPreferences? = null

    /** 文件管理上次待的目录 */
    var lastDir by mutableStateOf("")
        private set

    /** 书柜上次翻开的那本 */
    var lastBookId by mutableStateOf("")
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_ui", Context.MODE_PRIVATE)
        sp = p
        lastDir = p.getString("last_dir", "") ?: ""
        lastBookId = p.getString("last_book", "") ?: ""
    }

    fun saveDir(path: String) {
        if (path.isBlank() || path == lastDir) return
        lastDir = path
        sp?.edit()?.putString("last_dir", path)?.apply()
    }

    fun saveBook(id: String) {
        if (id.isBlank() || id == lastBookId) return
        lastBookId = id
        sp?.edit()?.putString("last_book", id)?.apply()
    }
}
