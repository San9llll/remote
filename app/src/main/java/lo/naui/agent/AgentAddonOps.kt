package lo.naui.agent

import android.content.Context
import android.content.Intent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 *本地功能（插件）要用到的几件小事，抽出来免得散在渲染器里。
 *
 * ##为什么单独一个文件
 *
 * Agent写的页面里，按钮能干的活儿就那几种（shell / open / http / goto）。
 *这几种在「页面」和「悬浮窗」两边都要用，但两边的**线程模型不一样**
 * （页面在 Compose协程里，悬浮窗在裸 Thread里）。所以这里给的是
 * **同步版**（`xxxBlocking`）——谁都能调，协程那边包一层就行。
 *
 * ##目录约定
 *
 * ```
 * addons/<id>/addon.json ←说这是什么
 * addons/<id>/pages.json ←应用内页面
 * addons/<id>/ui/welcome.json ←悬浮窗
 * ```
 *
 * Agent可以直接用 write_file往这些路径里写，也可以用 [scaffold]生成一份模板
 *再改 ——后者能省掉它"猜 schema"的那几轮往返。
 */
object AgentAddonOps {

	/**功能的根目录 */
	fun root(ctx: Context): File =
		File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "addons").apply { mkdirs() }

	/* ----------------给 Agent用的三个动作 ---------------- */

	/**列一下现在有哪些本地功能 */
	fun list(ctx: Context): ToolResult {
		val ks = root(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
			?: return ToolResult(true, "（还没有任何本地功能）目录：" + root(ctx).absolutePath)
		if (ks.isEmpty()) {
			return ToolResult(
				true,
				"（还没有任何本地功能）\n目录：" + root(ctx).absolutePath +
					"\n要新建就用 addon_new，它会连 manifest和模板一起写出来。",
			)
		}
		val sb = StringBuilder("共 " + ks.size + "个：\n")
		ks.forEach { d ->
			val mf = File(d, "addon.json")
			val o = runCatching { if (mf.isFile) JSONObject(mf.readText()) else null }.getOrNull()
			val pages = File(d, "pages.json")
			sb.append("· ").append(d.name)
				.append(" ").append(o?.optString("name", "") ?: "")
				.append(" kind=").append(o?.optString("kind", "page") ?: "?")
				.append(if (pages.isFile) "有 pages.json" else " （还没写 pages.json）")
				.append('\n')
		}
		sb.append("\n目录：").append(root(ctx).absolutePath)
		return ToolResult(true, sb.toString())
	}

	/**
	 *新建一个本地功能，**连模板一起写出来**。
	 *
	 *为什么要模板：pages.json的 schema对模型来说是"猜"——它第一次写十有八九
	 *少字段、或者把 blocks写成一个对象。给一份能跑的模板，它照着改就行，省好几轮。
	 */
	fun scaffold(ctx: Context, id0: String, name: String, kind: String): ToolResult {
		val id = id0.trim().ifBlank { "addon_" + System.currentTimeMillis() %100000 }
		if (id.contains('/') || id.contains("..")) {
			return ToolResult(false, "id不能带 /和 ..：" + id)
		}
		val dir = File(root(ctx), id)
		dir.mkdirs()
		File(dir, "ui").mkdirs()

		val k = if (kind in listOf("page", "window", "both")) kind else "page"
		val showName = name.trim().ifBlank { id }

		val manifest = JSONObject()
			.put("name", showName)
			.put("summary", "自己写的小功能")
			.put("kind", k)
			.put("version", "1.0")
		File(dir, "addon.json").writeText(manifest.toString(2))

		val pages = JSONObject()
			.put("start", "main")
			.put(
				"pages",
				JSONObject().put(
					"main",
					JSONObject()
						.put("title", showName)
						.put("subtitle", "改 pages.json就能改这一页")
						.put(
							"blocks",
							JSONArray()
								.put(JSONObject().put("type", "text").put("text", "这一页是模板，往下加东西就行。"))
								.put(
									JSONObject()
										.put("type", "list")
										.put("title", "系统负载")
										.put("action", "shell")
										.put("value", "cat /proc/loadavg")
								)
								.put(
									JSONObject()
										.put("type", "button")
										.put("text", "看看 /sdcard有什么")
										.put("action", "shell")
										.put("value", "ls -al /sdcard")
								)
						)
				)
			)
		File(dir, "pages.json").writeText(pages.toString(2))

		if (k == "window" || k == "both") {
			val win = JSONObject()
				.put("title", showName)
				.put("source", "cat /proc/loadavg")
				.put("interval", 5)
				.put("lines", 8)
				.put(
					"buttons",
					JSONArray().put(JSONObject().put("text", "关掉").put("action", "close"))
				)
			File(File(dir, "ui"), "welcome.json").writeText(win.toString(2))
		}

		return ToolResult(
			true,
			"建好了：" + dir.absolutePath + "\n" +
				" · addon.json（name / summary / kind / version）\n" +
				" · pages.json（start + pages.<名>，每个页面里是 blocks[]）\n" +
				(if (k == "page") "" else " · ui/welcome.json（悬浮窗）\n") +
				"\nblock能用的 type：text / card / list / button / input / divider\n" +
				"button和 list的 action：shell（value是命令）/ open（包名·链接·文件）/ http（网址）/ goto（页名）\n" +
				"要改就用 write_file直接改这几个 json。改完调一次 addon_reload，功能页当场就能看到。\n" +
				"悬浮窗想做成本地那种**贴边的侧边栏**（挂在屏幕边上、划开才出来），就在 welcome.json里加一行 edge，值写 right或 left。",
		)
	}

	/**把功能页那一栏刷一下（Agent改完 json必须调，不然界面还是旧的） */
	fun reload(ctx: Context): ToolResult {
		return runCatching {
			lo.naui.ui.addon.AddonStore.init(ctx)
			lo.naui.ui.addon.AddonStore.invalidate()
			ToolResult(true, "刷好了，现在有 " + lo.naui.ui.addon.AddonStore.addons.size + "个本地功能。")
		}.getOrElse { ToolResult(false, "刷不出来：" + (it.message ?: "")) }
	}

	/**把某个功能的悬浮窗开起来 /关掉 */
	fun window(ctx: Context, id: String, show: Boolean): ToolResult {
		if (id.isBlank()) return ToolResult(false, "要给 id（addons/下面那个目录名）")
		if (!show) {
			lo.naui.ui.addon.AddonFloatingService.hide(ctx)
			return ToolResult(true, "把悬浮窗关掉了。")
		}
		val spec = lo.naui.ui.addon.Addons.windowSpec(ctx, id)
			?: return ToolResult(
				false,
				"这个功能没有悬浮窗定义。要么 addon.json里 kind写 window / both，" +
					"要么写一份 addons/$id/ui/welcome.json。",
			)
		if (!lo.naui.ui.addon.AddonRuntime.canOverlay(ctx)) {
			return ToolResult(
				false,
				"还没有「显示在其他应用上层」的权限，飘不出来 ——" +
					"这个只能用户自己去「设置 →应用 →特殊权限 →悬浮窗」里给，" +
					"你告诉他一声，或者在功能页那个按钮上点一下就跳到系统页。",
			)
		}
		lo.naui.ui.addon.AddonFloatingService.show(ctx, id)
		return ToolResult(
			true,
			"飘起来了（" + spec.optString("title", id) + "）。" +
				"用户能在屏幕上拖着它走，×关掉（welcome.json里写了 edge的话，它是贴在屏幕边上、划开才出来的）。",
		)
	}

	/* ----------------页面 /悬浮窗两边共用 ---------------- */

	/**
	 *拉一段文本下来（给页面里的 http按钮用）。
	 *带硬上限，别让一页把内存吃光。
	 */
	suspend fun httpGet(url: String, limit: Int = 200_000): String = withContext(Dispatchers.IO) {
		if (!(url.startsWith("http://") || url.startsWith("https://"))) {
			return@withContext "地址得像 http://或 https://开头，给的是：" + url
		}
		runCatching {
			val conn = (URL(url).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				connectTimeout = 15_000
				readTimeout = 25_000
				setRequestProperty("User-Agent", "Nakour/1.03.0")
			}
			val code = conn.responseCode
			val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
				?.use { it.readBytes().toString(Charsets.UTF_8) }
				?: ""
			if (code !in 200..299) {
				// ⚠️只有失败路径才 disconnect。成功路径让 socket回 keep-alive池，
				//下一条能复用它 —— AgentApi那边踩过这个坑，注释还在。
				conn.disconnect()
				"HTTP $code\n" + body.take(2000)
			} else {
				body.take(limit)
			}
		}.getOrElse { "拉不到：" + (it.message ?: it.javaClass.simpleName) }
	}

	/**打开一个东西（同步版，悬浮窗的按钮和页面都用它） */
	fun openBlocking(ctx: Context, target: String): ToolResult {
		if (target.isBlank()) return ToolResult(false, "没给要打开的东西")
		val intent = runCatching {
			when {
				target.startsWith("http://") || target.startsWith("https://") ->
					Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))

				target.startsWith("/") || target.startsWith("file://") -> {
					val path = target.removePrefix("file://")
					val f = File(path)
					if (!f.exists()) return ToolResult(false, "没有这个文件：" + path)
					val uri = androidx.core.content.FileProvider.getUriForFile(
						ctx, ctx.packageName + ".fileprovider", f
					)
					Intent(Intent.ACTION_VIEW, uri)
						.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				}

				else -> ctx.packageManager.getLaunchIntentForPackage(target)
			}
		}.getOrNull()

		if (intent == null) {
			return ToolResult(false, "打不开：" + target + "（不是包名 /链接 /存在的文件？）")
		}
		return runCatching {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			ctx.startActivity(intent)
			ToolResult(true, "打开好了：" + target)
		}.getOrElse { ToolResult(false, "打不开：" + (it.message ?: it.javaClass.simpleName)) }
	}

	/**读一份 json */
	fun readJson(file: File): JSONObject? = runCatching {
		if (file.isFile) JSONObject(file.readText()) else null
	}.getOrNull()

	/**把 JSON数组当字符串列表 */
	fun strList(a: JSONArray?): List<String> {
		if (a == null) return emptyList()
		return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
	}
}
