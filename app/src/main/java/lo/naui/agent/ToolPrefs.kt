package lo.naui.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「这个会话能用哪些工具」。
 *
 * ##为什么单独一个文件、单独一份 SharedPreferences
 *
 *1. [AgentStore]管的是**配置**（服务商、温度、环境…），一个全局的东西。
 *工具开关是**按会话**的 ——会话 A想让模型放开手用 shell、会话 B只想让它读读文件，
 *这跟"配置"不是一回事，硬塞进那份文件只会让它更难读。
 *2. AgentStore.kt已经三百多行，而且它一改就是全 App的事。
 *这层单独放着，出问题也只影响"哪些工具发得出去"。
 *
 * ##约定
 *
 * **没挑过 =全开。**老会话不该因为升级完 App就突然少了一半工具 ——
 *那种"我什么都没改它却变了"的行为最让人怀疑人生。
 *挑过的那份才记下来；[reset]能把一个会话退回全开。
 */
object ToolPrefs {

	private var sp: SharedPreferences? = null

	/**会话 id →启用的工具名。变了要重画界面，所以是 mutableStateOf */
	private var table by mutableStateOf<Map<String, Set<String>>>(emptyMap())

	/**还没进任何会话时挑的那份落在这个 key上（当默认用） */
	const val GLOBAL = "__global__"

	private fun ensure(ctx: Context) {
		if (sp != null) return
		val p = ctx.getSharedPreferences("nakour_agent_tools", Context.MODE_PRIVATE)
		sp = p
		val m = mutableMapOf<String, Set<String>>()
		runCatching {
			p.all.forEach { (k, v) ->
				if (k.startsWith("c_") && v is String) {
					m[k.removePrefix("c_")] = v.split(",").filter { it.isNotBlank() }.toSet()
				}
			}
		}
		table = m
	}

	/**会话 id →存储 key */
	fun key(convId: String?): String = convId?.takeIf { it.isNotBlank() } ?: GLOBAL

	/**挑过没有。null =没挑过 =全开 */
	fun enabled(ctx: Context, convId: String?): Set<String>? {
		ensure(ctx)
		return table[key(convId)]
	}

	fun allowed(ctx: Context, convId: String?, name: String): Boolean {
		val e = enabled(ctx, convId) ?: return true
		return name in e
	}

	fun setEnabled(ctx: Context, convId: String?, name: String, on: Boolean) {
		ensure(ctx)
		val k = key(convId)
		//第一次挑：以"全开"为底再摘掉这一个，不然会只剩这一个
		val base = table[k] ?: catalog(ctx).map { it.first }.toSet()
		val cur = base.toMutableSet()
		if (on) cur += name else cur -= name
		table = table + (k to cur)
		sp?.edit()?.putString("c_" + k, cur.joinToString(","))?.apply()
	}

	/**退回"全开" */
	fun reset(ctx: Context, convId: String?) {
		ensure(ctx)
		val k = key(convId)
		table = table - k
		sp?.edit()?.remove("c_" + k)?.apply()
	}

	/**
	 *配置页那张清单：名字 /人话 /一句说明。
	 *
	 *每次调用都重新读一遍自定义工具（数量本来就不多，
	 *而"刚写完的工具要立刻出现在清单里"比省这点 IO重要）。
	 */
	fun catalog(ctx: Context): List<Triple<String, String, String>> {
		val builtin = AgentTools.ALL
			.filter { AgentGlassPrefs.webSearch || it.name != WebSearch.NAME }
			.map {
			Triple(
				it.name,
				CustomTools.displayName(it.name),
				it.description.lineSequence().firstOrNull()?.trim().orEmpty(),
			)
		}
		val mine = CustomTools.all(ctx).map {
			Triple(
				it.name,
				it.name + "（自己写的）",
				it.summary.ifBlank { it.command },
			)
		}
		return builtin + mine
	}

	/**
	 *这个会话该发给模型的工具表。
	 *
	 *内置的先按环境筛（沙箱不给 device_info），再拼上自定义工具，
	 *最后**一次性**按勾选过一遍 ——这么写是故意的：
	 * `AgentTools.toolsFor(env)`原样不动，别人再改那儿的过滤条件也不会漏掉这儿。
	 */
	fun toolsFor(ctx: Context, env: AgentEnv, convId: String?): List<AgentTool> {
		ensure(ctx)
		val all = AgentTools.toolsFor(env) + CustomTools.LIST_DEFS + CustomTools.defs(ctx)
		val picked = enabled(ctx, convId) ?: return all
		return all.filter { it.name in picked }
	}
}
