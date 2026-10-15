package lo.naui.sys

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 侧栏里那个快捷方式：指向功能页里的某个子页面 */
data class Shortcut(
    val key: String,
    val title: String,
)

/** 侧栏快捷栏最多放几个 */
const val MAX_SHORTCUTS = 20

/**
 * 侧边栏的「功能快捷栏」。
 *
 * 在功能页里同时按音量上+下就把当前页钉到左栏；在这个页面再按一次就摘掉。
 * 存本机，重启还在。
 */
object Shortcuts {

    private var sp: android.content.SharedPreferences? = null

    var items by mutableStateOf<List<Shortcut>>(emptyList())
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_shortcuts", Context.MODE_PRIVATE)
        sp = p
        items = runCatching {
            val arr = JSONArray(p.getString("items", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key", "")
                if (k.isBlank()) null else Shortcut(k, o.optString("title", k))
            }
        }.getOrDefault(emptyList())
    }

    fun has(key: String): Boolean = items.any { it.key == key }

    fun add(key: String, title: String) {
        if (has(key) || items.size >= MAX_SHORTCUTS) return
        items = items + Shortcut(key, title)
        persist()
    }

    fun remove(key: String) {
        items = items.filter { it.key != key }
        persist()
    }

    fun toggle(key: String, title: String) {
        if (has(key)) remove(key) else add(key, title)
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            items.forEach { arr.put(JSONObject().put("key", it.key).put("title", it.title)) }
            sp?.edit()?.putString("items", arr.toString())?.apply()
        }
    }
}
