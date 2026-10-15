package lo.naui.agent

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lo.naui.sys.Privilege
import org.json.JSONObject

/**
 *本机编译的「托管」。
 *
 * ##为什么要有它
 *
 *这台机器上编一版 APK要三四分钟，而 Agent手里只有 run_shell：
 *单条命令的等待是**按命令内容分档**的（见 AgentRunner.timeoutFor），
 *默认档只有60秒 ——构建压根等不完，于是模型只能
 * `起后台 → sleep → tail → sleep → tail`地空转。
 *用户的原话是「不用一直设置 sleep」；这台机器上真出过
 *几十次工具调用全花在轮询上。
 *
 * [wait]把「起构建」和「等结果」合到**一次**工具调用里：
 *起完就在这边循环看日志，**出结果才返回**，模型侧调一次就够。
 *
 * ##它不做什么
 *
 *不改脚本、不猜报错原因、不替你决定下一步。它只负责把
 * 「成功 /失败 +关键行 +日志末尾」摆出来。
 */
object AgentBuildOps {

	/**默认跑的构建脚本（Termux里那份） */
	const val DEFAULT_SCRIPT = "/data/data/com.termux/files/home/build-local.sh"

	/**默认日志落点 */
	const val DEFAULT_LOG = "/sdcard/我的/Agent/build.log"

	/**
	 *判定「出结果了」的标记。
	 *
	 *前两个是 Gradle自己打的，第三个是 build-local.sh里
	 * 「Gradle跑完了但工件没出来」那条 ——三种都算有结论。
	 */
	private val DONE = listOf("BUILD SUCCESSFUL", "BUILD FAILED", "没出 APK")

	/**多久看一次日志 */
	private const val POLL_MS =3_000L

	private fun str(desc: String): JSONObject =
		JSONObject().put("type", "string").put("description", desc)

	private fun int(desc: String): JSONObject =
		JSONObject().put("type", "integer").put("description", desc)

	private fun bool(desc: String): JSONObject =
		JSONObject().put("type", "boolean").put("description", desc)

	val DEF: AgentTool = AgentTool(
		name = AgentTools.BUILD,
		description = "起一次本机编译，**然后在这边一直守着，出结果才返回** —— " +
			"成功还是失败、关键报错行、日志末尾，一次给你。\n\n" +
			"⚠️这台机器上编一版要三四分钟，run_shell单条命令等不了那么久。" +
			"所以**别再「起后台 → sleep → tail」地空转**（用户原话：「不用一直设置 sleep」；" +
			"这台机器上真出过几十次工具调用全花在轮询上）。调这一条，等着就行。\n\n" +
			"默认跑 Termux里的 build-local.sh，日志落 /sdcard/我的/Agent/build.log，" +
			"最多等15分钟。只想守着已经在跑的那次，传 restart=false。\n" +
			"它是以 root跑那个脚本的（脚本在 Termux的私有目录里，本 app自己够不着），" +
			"所以跟沙箱 /本机这个环境开关关系不大。",
		properties = JSONObject()
			.put("script", str("要跑的构建脚本全路径。默认 " + DEFAULT_SCRIPT))
			.put("log", str("日志写到哪儿，默认 " + DEFAULT_LOG))
			.put("timeout", int("最多等多少秒，默认900（15分钟）"))
			.put("restart", bool("true（默认）=先重起一次构建；false =只守着已经在跑的那次")),
		required = emptyList(),
	)

	/* ----------------干活 ---------------- */

	suspend fun wait(ctx: Context, env: AgentEnv, args: JSONObject): ToolResult =
		withContext(Dispatchers.IO) {
			val script = args.optString("script", "").trim().ifBlank { DEFAULT_SCRIPT }
			val log = args.optString("log", "").trim().ifBlank { DEFAULT_LOG }
			val budget = args.optInt("timeout",900).coerceIn(30,3600)
			val restart = args.optBoolean("restart", true)

			//脚本在 Termux的私有目录里 ——这个 app自己是看不见的
			// （`File.isFile`在这儿会一路返回 false，那是 uid的墙，不是路径写错）。
			//所以「在不在、能不能跑」只能**以 root的身份**去问。
			val probe = Privilege.exec(ctx, "test -f " + q(script) + " && echo __here")
			if (probe == null || !probe.contains("__here")) {
				return@withContext ToolResult(
					false,
					"没找到那个脚本，或者拿不到 root：" + script + "\n" +
						"（这两样是同一件事的两个可能：su没放行，或者路径填错了。" +
						"默认那份在 " + DEFAULT_SCRIPT + "）",
				)
			}

			// ----起构建 ----
			if (restart) {
				runCatching { File(log).delete() }
				startBuild(script, log).let { if (!it.ok) return@withContext it }
			} else if (readLog(ctx, log) == null) {
				return@withContext ToolResult(
					false,
					"restart=false，但那个日志还没有内容：" + log +
						"（那就得 restart=true先起一次）",
				)
			}

			// ----守着，等出结果 ----
			val t0 = System.currentTimeMillis()
			var waited =0
			var seen: String? = null
			while (waited < budget) {
				delay(POLL_MS)
				waited = ((System.currentTimeMillis() - t0) /1000).toInt()
				val got = readLog(ctx, log)
				seen = got
				if (got != null && DONE.any { got.contains(it) }) break
			}

			//拎成不可变的局部值再往下用 ——避免在 lambda里被捕获带来的智能转换问题
			val report = seen
			if (report.isNullOrBlank()) {
				return@withContext ToolResult(
					false,
					"等了 " + waited + "秒，日志还是空的：" + log + "\n" +
						"说明构建压根没起来 ——先看看 su放没放行、脚本路径对不对。",
				)
			}

			val ok = report.contains("BUILD SUCCESSFUL")
			val failed = report.contains("BUILD FAILED") || report.contains("没出 APK")

			val sb = StringBuilder()
			sb.append(
				when {
					ok -> "结果：成功"
					failed -> "结果：失败"
					else -> "结果：等了 " + waited + "秒还没出结论（构建可能还在跑，" +
						"稍后 restart=false再看一次）"
				}
			).append('\n')
			sb.append("脚本：").append(script).append('\n')
			sb.append("日志：").append(log).append("（").append(report.length).append("字符）\n")

			//关键行：编译错误、Gradle的判决、脚本自己的那两行收尾
			val keys = report.lineSequence().map { it.trim() }
				.filter { t ->
					t.startsWith("e: ") ||
						(t.startsWith("w: ") && t.contains("unresolved")) ||
						t.contains("error:") || t.contains("FAILED") ||
						t.contains("BUILD SUCCESSFUL") || t.contains("BUILD FAILED") ||
						t.contains("Execution failed") || t.contains("没出 APK") ||
						t.contains("编好了") || t.contains("Caused by")
				}
				.toList()
			if (keys.isNotEmpty()) {
				sb.append("\n---关键行 ---\n")
				keys.takeLast(30).forEach { sb.append(it).append('\n') }
			}

			sb.append("\n---日志末尾40行 ---\n")
			report.lineSequence().toList().takeLast(40).forEach { sb.append(it).append('\n') }

			ToolResult(ok, sb.toString())
		}

	/**
	 *把构建丢到自己的会话里跑起来。
	 *
	 *两处讲究：
	 * · **setsid** ——不加这个，构建进程会跟着这次工具调用一起被收掉。
	 *之前用 `nohup … &`试过，工具调用一结束进程就没了。
	 * · **不等它跑完** ——这儿只等3秒，确认 su没当场翻脸就走，
	 *剩下的交给上面那个循环。
	 */
	private fun startBuild(script: String, log: String): ToolResult = runCatching {
		val pb = ProcessBuilder("su", "-c", "setsid /system/bin/sh " + q(script))
		// stderr跟着 stdout一起走，别让管子塞满把构建卡住
		pb.redirectErrorStream(true)
		pb.redirectOutput(ProcessBuilder.Redirect.to(File(log)))
		val p = pb.start()
		val done = p.waitFor(3, TimeUnit.SECONDS)
		if (done && p.exitValue() !=0) {
			ToolResult(false, "还没起就退了（su返回 " + p.exitValue() + "）。看日志：" + log)
		} else {
			ToolResult(true, "已起")
		}
	}.getOrElse { ToolResult(false, "起不来：" + (it.message ?: it.javaClass.simpleName)) }

	/**
	 *读日志。
	 *
	 *先当成普通文件读（/sdcard这层一般读得到，也快）；
	 *读不着就退到 root那句 `cat` ——日志可能是 root写的、本 app没权限。
	 */
	private suspend fun readLog(ctx: Context, log: String): String? {
		runCatching {
			val f = File(log)
			if (f.isFile) f.readText() else null
		}.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
		return Privilege.exec(ctx, "cat " + q(log))?.takeIf { it.isNotBlank() }
	}

	private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

	/**人话名（界面显示用，和 AgentChat里那张表对齐） */
	fun labelOf(name: String): String = if (name == AgentTools.BUILD) "编译托管" else name

	/**进度提示（"正在…"那一行） */
	fun hintOf(name: String): String = if (name == AgentTools.BUILD) "正在守着编译…" else "正在干活"
}
