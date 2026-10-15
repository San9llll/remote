package lo.naui.ui.addon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 *一个「本地功能」（插件）。就是磁盘上一份目录：
 *
 * ```
 * addons/我的工具/
 * addon.json ← manifest，说这是什么、有哪些页、是不是悬浮窗
 * pages.json ←页面内容（1.03.0的「页面结构」）
 * ui/welcome.json（可选）←悬浮窗长什么样
 * ```
 *
 * ##为什么做成「目录 + JSON」而不是编译进来的代码
 *
 *用户1.03.0的要求：**Agent能自己写功能，写完就出现在功能页里**。
 *编译进 APK的东西没法这么加 ——装完包就是死的。所以这一层的边界是：
 *
 * - **能改的**：界面长什么样（JSON描述）、点了按钮干什么（shell / open / http）
 * - **不能改的**：渲染器本身（@see AddonScreen / @see AddonFloatingService）
 *
 *这是刻意的：让 Agent往 JSON里写「画什么、点什么」，它写坏了顶多这一页难看，
 *不会把整个 App拖下水。真让它往 APK里塞 Dex才叫危险。
 */
data class AddonManifest(
	/**目录名，唯一 */
	val id: String,
	val name: String,
	val summary: String,
	/** page（应用内页面） / window（悬浮窗） / both */
	val kind: String,
	/**版本，Agent自己写 */
	val version: String,
	/** manifest的原始 JSON（留档用） */
	val json: JSONObject,
	val dir: File,
) {
	/**有悬浮窗？ */
	val isWindow: Boolean get() = kind == "window" || kind == "both"

	/**有应用内页面？（kind留空时默认当页面用） */
	val isPage: Boolean get() = kind == "page" || kind == "both" || kind.isBlank()
}

/**
 * addon.json / pages.json的读写。
 *
 *根目录：`Android/data/lo.naui/files/addons/`
 * （外部私有目录 ——文件管理器里看得见，用户也能自己往里扔 json）
 */
object Addons {

	/**所有功能的根目录 */
	fun root(ctx: Context): File =
		File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "addons").apply { mkdirs() }

	fun dirOf(ctx: Context, id: String): File = File(root(ctx), id)

	fun manifestFile(ctx: Context, id: String): File = File(dirOf(ctx, id), "addon.json")

	fun pagesFile(ctx: Context, id: String): File = File(dirOf(ctx, id), "pages.json")

	fun windowFile(ctx: Context, id: String): File = File(dirOf(ctx, id), "ui/welcome.json")

	/**列全部（目录名当 id，按名字排） */
	fun list(ctx: Context): List<AddonManifest> {
		val kids = root(ctx).listFiles() ?: return emptyList()
		return kids.filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { read(ctx, it.name) }
	}

	/**读一个 */
	fun read(ctx: Context, id: String): AddonManifest? {
		val dir = dirOf(ctx, id)
		if (!dir.isDirectory) return null
		val mf = manifestFile(ctx, id)
		val o = runCatching {
			if (mf.isFile) JSONObject(mf.readText()) else JSONObject()
		}.getOrElse { JSONObject() }
		return AddonManifest(
			id = id,
			name = o.optString("name", id),
			summary = o.optString("summary", ""),
			kind = o.optString("kind", "page"),
			version = o.optString("version", ""),
			json = o,
			dir = dir,
		)
	}

	/**页面定义（pages.json整个） */
	fun pages(ctx: Context, id: String): JSONObject? = runCatching {
		val f = pagesFile(ctx, id)
		if (!f.isFile) null else JSONObject(f.readText())
	}.getOrNull()

	/**悬浮窗定义：ui/welcome.json优先，没有就退回 pages.json里 kind=window的那页 */
	fun windowSpec(ctx: Context, id: String): JSONObject? {
		val wf = windowFile(ctx, id)
		val fromFile = runCatching { if (wf.isFile) JSONObject(wf.readText()) else null }.getOrNull()
		if (fromFile != null) return fromFile
		val pages = pages(ctx, id) ?: return null
		val all = pages.optJSONObject("pages") ?: return null
		val keys = all.keys()
		while (keys.hasNext()) {
			val k = keys.next()
			val p = all.optJSONObject(k) ?: continue
			if (p.optString("kind") == "window") return p
		}
		return null
	}

	/**起始页 */
	fun startPage(pages: JSONObject?): String =
		pages?.optString("start", "").takeUnless { it.isNullOrBlank() } ?: "main"

	/**有没有这一页 */
	fun hasPage(pages: JSONObject?, name: String): Boolean =
		pages?.optJSONObject("pages")?.has(name) == true

	/**取一页 */
	fun page(pages: JSONObject?, name: String): JSONObject? =
		pages?.optJSONObject("pages")?.optJSONObject(name)

	/**把一段 shell输出切成人看的行 */
	fun lines(s: String, cap: Int = 200): List<String> =
		s.split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }.take(cap)

	/**把 JSON数组当字符串列表读 */
	fun strList(a: JSONArray?): List<String> {
		if (a == null) return emptyList()
		return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
	}
}
