package lo.naui.ui.addon

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 *本地功能（插件）的注册表 ——功能页那一栏读的就是它。
 *
 *跟主题、Agent配置一样是个 object + mutableStateOf：
 * Agent在后台写完一个新功能，只要调一下 [refresh]，
 * **功能页当场就多一张卡**，不用重启 App。
 */
object AddonStore {

	private var ctxRef: Context? = null

	/**当前所有功能 */
	var addons by mutableStateOf<List<AddonManifest>>(emptyList())
		private set

	/**上次刷新时的错（出错了给用户看，别静默失败） */
	var lastError by mutableStateOf("")
		private set

	fun init(ctx: Context) {
		if (ctxRef == null) {
			ctxRef = ctx.applicationContext
			AddonRuntime.attach(ctx)
			refresh()
		}
	}

	val ready: Boolean get() = ctxRef != null

	fun ctxOrNull(): Context? = ctxRef

	/**重新扫一遍磁盘。Agent写完新功能必须调它（工具内部已经调了）。 */
	fun refresh() {
		val c = ctxRef ?: return
		addons = runCatching { Addons.list(c) }
			.onFailure { lastError = it.message ?: it.javaClass.simpleName }
			.getOrDefault(emptyList())
	}

	fun byId(id: String): AddonManifest? = addons.firstOrNull { it.id == id }

	/* ----------------页面 ---------------- */

	private val pageCache = HashMap<String, JSONObject?>()

	/**这个功能的 pages.json（带缓存，Agent重写时会清） */
	fun pagesOf(id: String): JSONObject? {
		val c = ctxRef ?: return null
		if (pageCache.containsKey(id)) return pageCache[id]
		val p = Addons.pages(c, id)
		pageCache[id] = p
		return p
	}

	fun pageOf(id: String, page: String): JSONObject? = Addons.page(pagesOf(id), page)

	fun startPageOf(addon: AddonManifest): String = Addons.startPage(pagesOf(addon.id))

	fun pageExists(id: String, name: String): Boolean = Addons.hasPage(pagesOf(id), name)

	/**丢掉缓存（Agent改了 json之后） */
	fun invalidate(id: String? = null) {
		if (id == null) pageCache.clear() else pageCache.remove(id)
		refresh()
	}

	/* ----------------悬浮窗 ---------------- */

	/**这个功能的悬浮窗定义（没有就 null） */
	fun windowOf(id: String): JSONObject? {
		val c = ctxRef ?: return null
		return runCatching { Addons.windowSpec(c, id) }.getOrNull()
	}
}
