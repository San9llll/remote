package lo.naui.sys

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 用户自己加进来的外部存储目录（SAF），存 URI 字符串 */
object FileStore {

    private var sp: android.content.SharedPreferences? = null

    var safDirs by mutableStateOf<List<String>>(emptyList())
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_files", Context.MODE_PRIVATE)
        sp = p
        safDirs = (p.getString("saf_dirs", "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
        remarks = runCatching {
            val o = org.json.JSONObject(p.getString("saf_remarks", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it, "") }
        }.getOrDefault(emptyMap())
    }

    fun addSafDir(uri: String) {
        if (safDirs.contains(uri)) return
        safDirs = safDirs + uri
        sp?.edit()?.putString("saf_dirs", safDirs.joinToString("\n"))?.apply()
    }

    fun removeSafDir(uri: String) {
        safDirs = safDirs.filter { it != uri }
        sp?.edit()?.putString("saf_dirs", safDirs.joinToString("\n"))?.apply()
    }

    var remarks by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun remarkOf(uri: String): String = remarks[uri].orEmpty()

    fun setRemark(uri: String, text: String) {
        val next = remarks.toMutableMap()
        if (text.isBlank()) next.remove(uri) else next[uri] = text
        remarks = next
        persistRemarks()
    }

    private fun persistRemarks() {
        runCatching {
            val o = org.json.JSONObject()
            remarks.forEach { (k, v) -> o.put(k, v) }
            sp?.edit()?.putString("saf_remarks", o.toString())?.apply()
        }
    }

    fun label(uri: String): String = runCatching {
        Uri.decode(uri.substringAfterLast('/')).ifBlank { uri }
    }.getOrDefault(uri)
}
