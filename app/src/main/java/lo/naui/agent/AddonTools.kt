package lo.naui.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 *「本地功能」（插件）这套东西给 Agent的几个工具。
 *
 * ##为什么单独一个文件
 *
 * AgentTool.kt已经快700行了，工具定义里最长的几个（run_shell / download / archive）
 *都在这层。1.03.0给 Agent新开的这一整块能力 ——**让它自己写功能**——
 *从定义到执行都能自洽，所以整块搬出来放这儿，AgentTool里只留三处接线：
 *
 * ```
 * val ALL = listOf(… ) + AddonTools.LIST ←注册
 * else -> AddonTools.dispatch(...) ←执行
 * ```
 *
 * ##它到底能做什么
 *
 *一个"本地功能"就是磁盘上一份目录：
 *
 * ```
 * addons/<id>/addon.json ←名字、说明、kind（page / window / both）
 * addons/<id>/pages.json ←应用内页面：blocks[]（text / card / list / button / input）
 * addons/<id>/ui/welcome.json ←悬浮窗：跑哪条命令、几秒刷一次、有哪些按钮
 * ```
 *
 * Agent用 [NEW]建出模板、用 write_file改 json、用 [RELOAD]让功能页当场刷新、
 *用 [WINDOW]把它飘到屏幕上。**全程不用重装 App** ——这就是用户要的
 * "Agent可以编写功能，并放到功能页内"。
 *
 * ##边界（为什么只让它写 JSON）
 *
 *这一层的渲染器（ui/addon/AddonRenderer.kt）认得哪些 type是**写死的**。
 *写坏了顶多那一页难看，不会把 App拖下水。让 Agent往 APK里塞 Dex是另一个
 *量级的事（签名、加载器、权限），不做。
 */
object AddonTools {

	const val LIST = "addon_list"
	const val NEW = "addon_new"
	const val RELOAD = "addon_reload"
	const val WINDOW = "addon_window"

	private fun str(desc: String): JSONObject =
		JSONObject().put("type", "string").put("description", desc)

	private fun int(desc: String): JSONObject =
		JSONObject().put("type", "integer").put("description", desc)

	private fun boolean(desc: String): JSONObject =
		JSONObject().put("type", "boolean").put("description", desc)

	private fun act(desc: String, vararg options: String): JSONObject =
		JSONObject()
			.put("type", "string")
			.put("description", desc)
			.put("enum", JSONArray(options as Array<String>))

	val LIST_DEFS: List<AgentTool> = listOf(
		AgentTool(
			name = LIST,
			description = "看这台机器上现在有哪些**本地功能**（用户自己写的、或者你之前写的那些）。\n" +
				"每个功能就是 addons/下面一个目录：addon.json + pages.json（+悬浮窗的 ui/welcome.json）。\n" +
				"要改哪个功能，直接用 write_file改它的 json；改完记得调 " + RELOAD + "。",
			properties = JSONObject(),
			required = emptyList(),
		),
		AgentTool(
			name = NEW,
			description = "**新建一个本地功能**（一套能直接跑的模板一次写好）。\n" +
				"这是给这台 App **加新功能**的正路 ——用户要「写个 XX工具」「做个 XX面板」时用它，" +
				"别去改 APK里的代码（那要重新编译安装，做不到）。\n\n" +
				"建完目录长这样：\n" +
				"```\n" +
				"addons/<id>/\n" +
				" addon.json ← name / summary / kind / version\n" +
				" pages.json ←应用内页面（start + pages.<名>.blocks[]）\n" +
				" ui/welcome.json ←悬浮窗（kind是 window / both才有）\n" +
				"```\n\n" +
				"`blocks[]`里每一块的 type：\n" +
				" · `text` —一段字（text）\n" +
				" · `card` —一张卡（title + lines[]）\n" +
				" · `list` —跑一条命令，每行输出列出来，进页面就自动跑一次（title / action / value）\n" +
				" · `button` —按钮（text / action / value）\n" +
				" · `input` —一行输入（id / label），值能用 `{id}`插进命令里\n" +
				" · `divider` —一条分隔线\n\n" +
				"`action`有四种：`shell`（value是命令）/ `open`（包名·网址·文件路径）/ " +
				"`http`（网址，拉下来的正文贴出来）/ `goto`（value是页名）。\n" +
				"悬浮窗的 welcome.json：`title` / `source`（定时跑的命令）/ `interval`（秒）/ " +
				"`lines`（显示几行）/ `buttons[]`（text + action，action支持 close / collapse / expand / shell / open）。\n" +
				"**贴边侧边栏**（像系统自带那种：平时只露一条细柄、划一下才滑出来）：welcome.json里加一行 edge，值写 right（贴右边）或 left（贴左边）。\n" +
				"可配 panelWidth（面板宽，单位 dp，默认210，最多占屏幕72%）和 handleWidth（细柄宽，单位 dp，默认20，贴着屏幕最边上那条，点一下或者往外划都能把面板拉出来）；贴边模式下按钮的 action多了 collapse（收回去）和 expand（滑出来）。不写 edge就是原来那种能拖着走的小卡片。",
			properties = JSONObject()
				.put("id", str("目录名，英文小写最省事（比如 disk_panel）。留空会自动生成一个"))
				.put("name", str("显示名，中文也行"))
				.put("kind", act("要哪一种", "page", "window", "both")),
			required = listOf("name"),
		),
		AgentTool(
			name = RELOAD,
			description = "把功能页那一栏重新扫一遍。**你改完任何 addon.json / pages.json都要调它** ——\n" +
				"不调的话界面里还是旧内容（页面的 json是带缓存读的）。",
			properties = JSONObject().put("id", str("只刷这一个功能（留空刷全部）")),
			required = emptyList(),
		),
		AgentTool(
			name = WINDOW,
			description = "把一个本地功能的**悬浮窗**飘到屏幕上（或者关掉）。\n" +
				"飘上去之后它会按 welcome.json里写的 `interval`定时跑 `source`那条命令，" +
				"把输出贴在窗口里 ——用户能在屏幕上拖它，点 ×关掉；要是 welcome.json里写了 edge（right / left），它改成挂在屏幕边上，平时只露一条细柄，划开才出来。\n\n" +
				"⚠️要有「显示在其他应用上层」的权限才飘得起来。没有的话这条会告诉你，" +
				"你照实跟用户说：让他去功能页那个按钮点一下，跳到系统页开一下。",
			properties = JSONObject()
				.put("id", str("哪个功能（目录名）"))
				.put("show", boolean("true打开 / false关掉，默认打开")),
			required = listOf("id"),
		),
	)

	/**不是 addon工具就返回 null，让 AgentTool那边继续报"没有这个工具" */
	suspend fun dispatch(ctx: Context, name: String, args: JSONObject): ToolResult? = when (name) {
		LIST -> AgentAddonOps.list(ctx)

		NEW -> AgentAddonOps.scaffold(
			ctx,
			args.optString("id", "").trim(),
			args.optString("name", "").trim(),
			args.optString("kind", "page").trim(),
		)

		RELOAD -> {
			val one = args.optString("id", "").trim()
			val r = AgentAddonOps.reload(ctx)
			if (r.ok && one.isNotBlank()) ToolResult(true, r.output + "\n（刷的是全部，含 " + one + "）") else r
		}

		WINDOW -> AgentAddonOps.window(
			ctx,
			args.optString("id", "").trim(),
			args.optBoolean("show", true),
		)

		else -> null
	}

	/**人话名（界面显示用，和 AgentChat里那张表对齐） */
	fun labelOf(name: String): String = when (name) {
		LIST -> "看本地功能"
		NEW -> "新建本地功能"
		RELOAD -> "刷新功能页"
		WINDOW -> "开关悬浮窗"
		else -> name
	}

	/**进度提示（"正在…"那一行） */
	fun hintOf(name: String): String = when (name) {
		LIST -> "正在看有哪些本地功能"
		NEW -> "正在建一个本地功能"
		RELOAD -> "正在刷功能页"
		WINDOW -> "正在弄悬浮窗"
		else -> "正在干活"
	}
}
