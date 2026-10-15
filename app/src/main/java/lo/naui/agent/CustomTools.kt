package lo.naui.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「Agent自己写出来的工具」。
 *
 * ##和「本地功能」(ui/addon)的区别
 *
 * -本地功能是**给人看的界面**：应用内页面、悬浮窗，列在功能页里
 * -这里是**给模型用的工具**：写进来就进 function calling那张表，模型能直接调
 *
 *一个工具 = `files/customtools`里的一份 json（目录名见 [dir]）：
 *
 * ```json
 * {
 * "name": "disk_free",
 * "summary": "看各分区还剩多少空间",
 * "command": "df -h {mount}",
 * "params": [{"name":"mount","type":"string","desc":"挂载点","required":false}]
 * }
 * ```
 *
 * `command`里用 `{参数名}`占位，调用的时候按类型换进去：
 * string会**裹一层 shell单引号**（防着参数里塞 `; rm -rf /`），
 * integer / boolean直接当数字用。
 *
 * ##为什么敢让它这么干
 *
 *用户要「让 Agent自己写工具并加入」。边界定在：它写的是
 * **一条命令模板 +一张参数表**，不是往 APK里塞 Dex（那要签名、加载器、权限，另一个量级）。
 *所以写坏了顶多那个工具报错，不会把 App拖下水。
 *而且执行的时候**照样过 [DangerGuard]那道闸门** ——危险命令还是要用户点头，
 *这条不能因为"工具是它自己写的"就松掉。
 */
data class CustomTool(
	/**文件名（去 .json），唯一 */
	val id: String,
	val name: String,
	val summary: String,
	val command: String,
	val params: List<Param>,
) {
	data class Param(
		val name: String,
		val type: String,
		val desc: String,
		val required: Boolean,
	)

	/**变成模型看得懂的那张表 */
	fun toAgentTool(): AgentTool {
		val props = JSONObject()
		params.forEach { p ->
			val o = JSONObject().put("type", p.type)
			if (p.desc.isNotBlank()) o.put("description", p.desc)
			props.put(p.name, o)
		}
		return AgentTool(
			name = name,
			description = summary.ifBlank { "用户自定义的工具「" + name + "」" } +
				"\n（这是这个设备上自己写出来的工具，底层跑的是：`" + command.take(160) + "`）",
			properties = props,
			required = params.filter { it.required }.map { it.name },
		)
	}

	/**参数换成值 ——字符串类型的**必须**包单引号，不然参数里带的空格 /分号会把命令拆开 */
	fun quote(p: Param, args: JSONObject): String = when (p.type) {
		"integer" -> args.optInt(p.name,0).toString()
		"boolean" -> if (args.optBoolean(p.name, false)) "1" else "0"
		else -> "'" + args.optString(p.name, "").replace("'", "'\\''") + "'"
	}

	/**把模板渲染成一条真命令 */
	fun render(args: JSONObject): String {
		var s = command
		params.forEach { p ->
			s = s.replace("{" + p.name + "}", quote(p, args))
		}
		return s
	}

	/**必填没给全的那几个 */
	fun missing(args: JSONObject): List<String> =
		params.filter { it.required && args.optString(it.name, "").isBlank() }.map { it.name }

	fun toJson(): JSONObject {
		val arr = JSONArray()
		params.forEach { p ->
			arr.put(
				JSONObject()
					.put("name", p.name)
					.put("type", p.type)
					.put("desc", p.desc)
					.put("required", p.required)
			)
		}
		return JSONObject()
			.put("name", name)
			.put("summary", summary)
			.put("command", command)
			.put("params", arr)
	}

	companion object {
		/**只让字母数字下划线，字母或下划线开头 ——工具名要能当 json的 key用 */
		val NAME_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]{1,31}$")

		val TYPES = listOf("string", "integer", "boolean")

		fun from(o: JSONObject, id: String): CustomTool? {
			val n = o.optString("name", "").trim()
			val c = o.optString("command", "").trim()
			if (n.isBlank() || c.isBlank()) return null
			val ps = mutableListOf<Param>()
			val arr = o.optJSONArray("params")
			if (arr != null) {
				for (i in 0 until arr.length()) {
					val p = arr.optJSONObject(i) ?: continue
					val pn = p.optString("name", "").trim()
					if (pn.isBlank()) continue
					val ty = p.optString("type", "string").trim().lowercase()
					ps += Param(
						name = pn,
						type = if (ty in TYPES) ty else "string",
						desc = p.optString("desc", ""),
						required = p.optBoolean("required", false),
					)
				}
			}
			return CustomTool(id, n, o.optString("summary", ""), c, ps)
		}
	}
}

object CustomTools {

	const val NEW = "tool_new"
	const val LIST = "tool_list"
	const val DEL = "tool_delete"

	private fun str(desc: String): JSONObject =
		JSONObject().put("type", "string").put("description", desc)

	fun dir(ctx: Context): File = File(ctx.filesDir, "customtools").apply { mkdirs() }

	/* ----------------存 /读 ---------------- */

	fun all(ctx: Context): List<CustomTool> {
		val d = dir(ctx)
		val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
		return files.sortedBy { it.name }.mapNotNull { f ->
			runCatching {
				CustomTool.from(JSONObject(f.readText()), f.name.removeSuffix(".json"))
			}.getOrNull()
		}
	}

	fun find(ctx: Context, name: String): CustomTool? = all(ctx).firstOrNull { it.name == name }

	/**给模型的那张表 */
	fun defs(ctx: Context): List<AgentTool> = all(ctx).map { it.toAgentTool() }

	/* ----------------三个工具（给 Agent用） ---------------- */

	val LIST_DEFS: List<AgentTool> = listOf(
		AgentTool(
			name = NEW,
			description = "**自己写一个新工具，写完立刻就能调**（下一轮它就出现在你的工具表里，不用重启）。\n\n" +
				"什么时候该用它：同一串命令让你反复拼（比如每次都 `cat /sys/class/power_supply/…`）、" +
				"或者用户说「给我加个 XX工具」的时候 ——把它固化成一个带参数的工具，以后一句话就能调。\n\n" +
				"`command`是命令模板，用 `{参数名}`占位。`params`给参数表：" +
				"标准写法是 JSON数组字符串，也可以偷懒写成 `挂载点:mount:string`那种逗号分隔。\n" +
				"类型只有 string / integer / boolean三种。\n\n" +
				"⚠️执行走的是和 `run_shell`同一套围栏 +危险闸门 —— " +
				"别写 `rm -rf /`那种，写了一样会被拦下来问用户。",
			properties = JSONObject()
				.put("name", str("工具名：字母或下划线开头，只能用字母数字下划线，比如 disk_free"))
				.put("summary", str("一句话说明它干什么 ——模型就是靠这句决定什么时候调的"))
				.put("command", str("命令模板，用 {参数名}占位。比如 df -h {mount}"))
				.put("params", str("参数表。JSON数组字符串，或者简写 mount:string:挂载点,deep:integer:往下几层")),
			required = listOf("name", "command"),
		),
		AgentTool(
			name = LIST,
			description = "看看现在有哪些**自己写的工具**。\n" +
				"要改哪个，先 [删除]再 [新建]（模板就是一条命令，不值得给它做编辑器）。",
			properties = JSONObject(),
			required = emptyList(),
		),
		AgentTool(
			name = DEL,
			description = "删掉一个自己写的工具。内置工具删不掉（这条只认自定义的）。",
			properties = JSONObject().put("name", str("要删的那个工具名")),
			required = listOf("name"),
		),
	)

	/* ----------------执行 ---------------- */

	/**
	 *不是自定义工具就返回 null，让上层继续报"没有这个工具"。
	 *
	 * [env]一路传下来是必要的：自定义工具跑的还是 shell，
	 *沙箱 /本机那套围栏得照旧生效。
	 */
	suspend fun dispatch(
		ctx: Context,
		env: AgentEnv,
		name: String,
		args: JSONObject,
	): ToolResult? = when (name) {
		NEW -> newTool(ctx, args)
		LIST -> listTools(ctx)
		DEL -> deleteTool(ctx, args)
		else -> {
			val t = find(ctx, name) ?: return null
			runTool(ctx, env, t, args)
		}
	}

	private fun newTool(ctx: Context, args: JSONObject): ToolResult {
		val name = args.optString("name", "").trim()
		val cmd = args.optString("command", "").trim()
		val summary = args.optString("summary", "").trim()
		if (name.isBlank() || cmd.isBlank()) {
			return ToolResult(false, "name和 command都得给 ——光有名字没法干活。")
		}
		if (!CustomTool.NAME_RE.matches(name)) {
			return ToolResult(
				false,
				"名字不合规：「" + name + "」。只能用字母数字下划线，字母或下划线开头，" +
					"长度2~32（比如 disk_free）。",
			)
		}
		if (AgentTools.ALL.any { it.name == name }) {
			return ToolResult(false, "内置工具里已经有「" + name + "」了，换个名字。")
		}
		val old = find(ctx, name)
		if (old != null) {
			return ToolResult(
				false,
				"已经有一个自己写的工具叫「" + name + "」了（跑的是 `" + old.command.take(80) + "`）。" +
					"要么换个名字，要么先 " + DEL + "掉它再建。",
			)
		}

		val ps = parseParams(args.optString("params", ""))
		//模板里出现的占位符没在参数表里登记 ——十有八九是它写漏了，直说
		val holes = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}").findAll(cmd)
			.map { it.groupValues[1] }
			.filter { h -> ps.none { it.name == h } }
			.toList()
		if (holes.isNotEmpty()) {
			return ToolResult(
				false,
				"模板里用了这些占位符：" + holes.joinToString("、") +
					"但 params里没登记它们 ——补上参数表，或者把模板里那几个花括号改掉。",
			)
		}

		val t = CustomTool(name, name, summary, cmd, ps)
		runCatching {
			File(dir(ctx), name + ".json").writeText(t.toJson().toString(2))
		}.onFailure { return ToolResult(false, "写不下去：" + (it.message ?: "")) }

		return ToolResult(
			true,
			"建好了：**" + name + "**" + (if (summary.isBlank()) "" else "（" + summary + "）") + "\n" +
				"参数：" + (if (ps.isEmpty()) "（没有）" else ps.joinToString("、") {
					it.name + ":" + it.type + (if (it.required) "(必填)" else "")
				}) + "\n" +
				"底层命令：" + cmd + "\n\n" +
				"下一轮它就在你的工具表里了，直接调就行。" +
				"要改就先 " + DEL + "再 " + NEW + "。",
		)
	}

	private fun listTools(ctx: Context): ToolResult {
		val all = all(ctx)
		if (all.isEmpty()) {
			return ToolResult(
				true,
				"（还没有自己写的工具）目录：" + dir(ctx).absolutePath +
					"\n想加就用 " + NEW + " ——一条命令模板 +一张参数表就够了。",
			)
		}
		val sb = StringBuilder("自己写的有 " + all.size + "个：\n")
		all.forEach { t ->
			sb.append("· ").append(t.name)
				.append(if (t.summary.isBlank()) "" else " — " + t.summary)
				.append("\n `").append(t.command.take(100)).append("`")
				.append(if (t.params.isEmpty()) "" else " (" + t.params.joinToString(",") { it.name } + ")")
				.append('\n')
		}
		sb.append("\n目录：").append(dir(ctx).absolutePath)
		return ToolResult(true, sb.toString())
	}

	private fun deleteTool(ctx: Context, args: JSONObject): ToolResult {
		val name = args.optString("name", "").trim()
		if (name.isBlank()) return ToolResult(false, "要给 name（tool_list能看到有哪些）")
		val f = File(dir(ctx), name + ".json")
		if (!f.isFile) return ToolResult(false, "没有这个自定义工具：" + name)
		val ok = runCatching { f.delete() }.getOrDefault(false)
		return if (ok) ToolResult(true, "删掉了「" + name + "」，它下一轮就不在工具表里了。")
		else ToolResult(false, "删不掉：" + f.absolutePath)
	}

	/**
	 *真跑一个自定义工具。
	 *
	 * ⚠️危险闸门在这儿必须再过一遍 ——不然后门就开了：
	 *谁都能写个叫 `x`的工具、里面塞 `dd of=/dev/block/...`，
	 *走这条路就绕过了 run_shell那套检查。
	 */
	private suspend fun runTool(
		ctx: Context,
		env: AgentEnv,
		t: CustomTool,
		args: JSONObject,
	): ToolResult {
		val miss = t.missing(args)
		if (miss.isNotEmpty()) {
			return ToolResult(
				false,
				"缺必填参数：" + miss.joinToString("、") +
					"（模板：" + t.command + "）",
			)
		}
		val cmd = t.render(args)

		DangerGuard.check(cmd)?.let { hit ->
			when (AgentStore.dangerPolicy) {
				DangerGuard.Policy.Deny -> return ToolResult(
					false,
					"这个动作被安全策略挡下来了（" + hit.category.label + "）。",
				)
				DangerGuard.Policy.Allow -> Unit
				DangerGuard.Policy.Ask -> return ToolResult(
					ok = false,
					output = "自己写的工具「" + t.name + "」要跑一条危险命令（" + hit.category.label +
						"），得先经用户同意；同意后会自动执行，不用你再发一遍。",
					decision = RiskDecision(
						kind = RiskKind.Danger,
						detail = cmd,
						note = hit.category.label + " ·来自自定义工具 " + t.name,
						consequence = hit.category.consequence,
						run = { AgentRunner.shell(ctx, env, cmd) },
					),
				)
			}
		}

		val r = AgentRunner.shell(ctx, env, cmd)
		return ToolResult(
			r.ok,
			"（这是自己写的工具 " + t.name + "跑出来的）\n" + r.output,
			r.decision,
		)
	}

	/**
	 *参数表：先按 JSON数组解，解不动就按 `名字:类型:说明`逗号分隔解。
	 *
	 *两条路都留着是因为模型两种写法都爱用 ——只有一条的话，
	 *另一种写法它就会拿到一句"格式不对"，然后重试一整轮。
	 */
	private fun parseParams(raw: String): List<CustomTool.Param> {
		val t = raw.trim()
		if (t.isBlank()) return emptyList()
		if (t.startsWith("[")) {
			val out = mutableListOf<CustomTool.Param>()
			runCatching {
				val arr = JSONArray(t)
				for (i in 0 until arr.length()) {
					val o = arr.optJSONObject(i) ?: continue
					val n = o.optString("name", "").trim()
					if (n.isBlank()) continue
					val ty = o.optString("type", "string").trim().lowercase()
					out += CustomTool.Param(
						name = n,
						type = if (ty in CustomTool.TYPES) ty else "string",
						desc = o.optString("desc", ""),
						required = o.optBoolean("required", false),
					)
				}
			}
			return out
		}
		//简写路：mount:string:挂载点,deep:integer:往下几层
		return t.split(",").mapNotNull { one ->
			val parts = one.split(":")
			val n = parts.getOrNull(0)?.trim().orEmpty()
			if (n.isBlank() || !CustomTool.NAME_RE.matches(n)) return@mapNotNull null
			val ty = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
			CustomTool.Param(
				name = n,
				type = if (ty in CustomTool.TYPES) ty else "string",
				desc = parts.getOrNull(2)?.trim().orEmpty(),
				required = false,
			)
		}
	}

	/**
	 *工具名 →人话（配置页勾选那张清单用）。
	 *
	 * ⚠️和 AgentChat里那份 friendlyName是同一张表的两个副本。
	 *之所以没合并：tools/check_tool_registration.py是**按名字扫 AgentChat里那张表**的，
	 *挪走它就会报红。等那个脚本改成扫这边，可以把 AgentChat那份收掉。
	 */
	fun displayName(name: String): String = when (name) {
		AgentTools.SHELL -> "执行命令"
		AgentTools.BATCH -> "批量执行"
		AgentTools.READ_MANY -> "批量读文件"
		AgentTools.FIND -> "找文件"
		AgentTools.GREP -> "搜内容"
		AgentTools.READ -> "读文件"
		AgentTools.WRITE -> "写文件"
		AgentTools.LIST -> "看目录"
		AgentTools.DEVICE -> "看设备信息"
		AgentTools.DOWNLOAD -> "后台下载"
		AgentTools.DOWNLOAD_CHECK -> "问下载进度"
		AgentTools.FILE_OP -> "文件操作"
		AgentTools.DELETE -> "删除"
		AgentTools.TREE -> "看目录树"
		AgentTools.STAT -> "看文件信息"
		AgentTools.ARCHIVE -> "压缩包"
		AgentTools.APPS -> "列应用"
		AgentTools.OPEN -> "打开应用/文件"
		AgentTools.CLIPBOARD -> "剪贴板"
		AgentTools.NOTICE -> "发通知"
		AgentTools.SHOT -> "截屏"
		AgentTools.BUILD -> "编译托管"
		AddonTools.LIST -> "看本地功能"
		AddonTools.NEW -> "新建本地功能"
		AddonTools.RELOAD -> "刷新功能页"
		AddonTools.WINDOW -> "开关悬浮窗"
		NEW -> "写一个新工具"
		LIST -> "看自己写的工具"
		DEL -> "删掉自己写的工具"
		else -> name
	}
}
