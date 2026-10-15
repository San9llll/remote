package lo.naui.ui.addon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lo.naui.agent.AgentAddonOps
import lo.naui.agent.AgentRunner
import lo.naui.agent.AgentStore
import lo.naui.agent.ToolResult
import lo.naui.ui.common.PageHeader
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 *本地功能的**渲染器**。
 *
 * ##这层是 Agent和界面之间的「兼容层」
 *
 * Agent没法编译 APK，但它能写 JSON。所以约定是：
 * Agent在 `addons/<id>/pages.json`里描述「这一页放什么、点了按钮干什么」，
 *这一层负责把那份描述画出来。**它认识的积木是固定的一小套**：
 *
 * | type |干什么 |
 * |---|---|
 * | text |一段说明文字 |
 * | card |一张卡，标题 +若干行 |
 * | list |跑一条命令，把每行输出列出来（进页面自动跑一次） |
 * | button |按钮：shell / open / http / goto |
 * | input |一行输入，值可以用 `{id}`插到命令里 |
 * | divider |分隔线 |
 *
 *为什么定这么死：**Agent写坏了顶多这一页难看**，不会把 App拖下水。
 *让它往 APK里塞 Dex是另一个量级的事（签名、加载器、权限），不做。
 *
 * ##状态
 *
 *每页一份 [AddonPageState]（输入框的值 +每块自己的输出）。
 *切页就重建，所以别指望它记事 ——要记事让 Agent把东西写进文件（shell能写）。
 */
@Composable
fun AddonScreen(
	addon: AddonManifest,
	startPage: String? = null,
	onBack: () -> Unit,
	backdrop: com.kyant.backdrop.Backdrop? = null,
) {
	val ctx = LocalContext.current
	//渲染器里那些回调拿不到 CompositionLocal，进来先把 Context存一份
	AddonRuntime.attach(ctx)

	val pages = remember(addon.id) { AddonStore.pagesOf(addon.id) }
	val home = Addons.startPage(pages)
	val pageName = remember(addon.id) { androidx.compose.runtime.mutableStateOf(startPage ?: home) }
	val page = Addons.page(pages, pageName.value)

	//每页一份状态：输出 +输入
	val state = remember(addon.id, pageName.value) { AddonPageState() }
	val scope = rememberCoroutineScope()

	if (page == null) {
		//还没写 pages.json，或者 Agent写歪了 ——别白屏
		AddonFallback(addon = addon, onBack = onBack)
		return
	}

	Column(Modifier.fillMaxSize()) {
		PageHeader(
			title = page.optString("title", addon.name),
			subtitle = page.optString("subtitle", addon.summary).takeIf { it.isNotBlank() },
			onBack = { if (pageName.value == home) onBack() else pageName.value = home },
		)

		Column(
			Modifier
				.weight(1f)
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 14.dp),
		) {
			val blocks = page.optJSONArray("blocks")
			Spacer(Modifier.height(6.dp))
			if (blocks == null || blocks.length() == 0) {
				Text(
					"这一页还是空的。可以让 Agent往 addons/" + addon.id + "/pages.json里加点东西。",
					fontSize = 12.sp,
					color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
				)
			} else {
				for (i in 0 until blocks.length()) {
					val b = blocks.optJSONObject(i) ?: continue
					AddonBlock(
						b = b,
						index = i,
						state = state,
						onAction = { act, value ->
							runAddonAction(scope, state, i, act, value) { pageName.value = it }
						},
						modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
					)
				}
			}
			Spacer(Modifier.height(28.dp))
		}
	}
}

/**
 *一页的运行期状态。
 *
 *用 Compose的 state map：改了会自动重组 ——
 *不然「点了按钮、输出贴上去了」界面不刷新，看着像没反应。
 */
class AddonPageState {
	/**每个块的输出，键是块在 blocks[]里的下标 */
	val out = mutableStateMapOf<Int, String>()

	/**输入框的值：id →文本 */
	val inputs = mutableStateMapOf<String, String>()

	fun text(i: Int): String = out[i] ?: ""
}

/**一个块的分发 */
@Composable
private fun AddonBlock(
	b: JSONObject,
	index: Int,
	state: AddonPageState,
	onAction: (String, String) -> Unit,
	modifier: Modifier,
) {
	when (b.optString("type", "text")) {

		"text" -> Text(
			b.optString("text", ""),
			fontSize = (b.optInt("size", 13)).sp,
			color = if (b.optBoolean("dim", false)) MiuixTheme.colorScheme.onSurfaceVariantSummary
			else MiuixTheme.colorScheme.onSurface,
			modifier = modifier,
		)

		"divider" -> Box(
			modifier
				.height(1.dp)
				.background(MiuixTheme.colorScheme.dividerLine)
		)

		"card" -> {
			val lines = Addons.strList(b.optJSONArray("lines"))
			Column(
				modifier
					.clip(RoundedCornerShape(16.dp))
					.background(MiuixTheme.colorScheme.surfaceContainerHigh)
					.padding(14.dp),
			) {
				Text(b.optString("title", ""), fontSize = 14.sp, fontWeight = FontWeight.Medium)
				lines.forEach { l ->
					Spacer(Modifier.height(3.dp))
					Text(l, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
			}
		}

		"input" -> Column(modifier) {
			val id = b.optString("id", "in" + index)
			Text(
				b.optString("label", id),
				fontSize = 11.5.sp,
				color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
			)
			Spacer(Modifier.height(4.dp))
			Box(
				Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(10.dp))
					.background(MiuixTheme.colorScheme.surfaceContainerHigh)
					.padding(horizontal = 12.dp, vertical = 9.dp),
			) {
				BasicTextField(
					value = state.inputs[id] ?: b.optString("default", ""),
					onValueChange = { state.inputs[id] = it },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					textStyle = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
					cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
				)
			}
		}

		"button" -> Column(modifier) {
			Box(
				Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(50))
					.background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
					.clickable { onAction(b.optString("action", "shell"), b.optString("value", "")) }
					.padding(vertical = 11.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					b.optString("text", "点我"),
					fontSize = 13.sp,
					color = MiuixTheme.colorScheme.primary,
				)
			}
			AddonOutput(state.text(index))
		}

		"list" -> {
			val src = b.optString("action", "shell")
			val cmd = b.optString("value", "")
			//进页面就跑一次（跑过了就不重跑 ——state里已经有输出）
			LaunchedEffect(index, cmd) {
				if (cmd.isNotBlank() && state.text(index).isBlank()) {
					onAction(src, cmd)
				}
			}
			Column(modifier) {
				Text(b.optString("title", ""), fontSize = 13.sp, fontWeight = FontWeight.Medium)
				Spacer(Modifier.height(6.dp))
				Box(
					Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(12.dp))
						.background(Color.Black.copy(alpha = 0.28f))
						.padding(12.dp),
				) {
					Text(
						state.text(index).ifBlank { "（还没跑）" },
						fontSize = 11.5.sp,
						fontFamily = FontFamily.Monospace,
						color = MiuixTheme.colorScheme.onSurface,
					)
				}
				Row(
					Modifier.fillMaxWidth().padding(top = 6.dp),
					horizontalArrangement = Arrangement.End,
				) {
					Text(
						"重跑",
						fontSize = 12.sp,
						color = MiuixTheme.colorScheme.primary,
						modifier = Modifier
							.clip(RoundedCornerShape(50))
							.clickable { onAction(src, cmd) }
							.padding(horizontal = 10.dp, vertical = 4.dp),
					)
				}
			}
		}

		else -> Text(
			"（这一块类型还不认识：" + b.optString("type", "") + "）",
			fontSize = 11.sp,
			color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
			modifier = modifier,
		)
	}
}

@Composable
private fun AddonOutput(s: String) {
	if (s.isBlank()) return
	Spacer(Modifier.height(6.dp))
	Box(
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(Color.Black.copy(alpha = 0.28f))
			.padding(12.dp),
	) {
		Text(s, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
	}
}

/**没写 pages.json时的兜底页：告诉用户（和 Agent）该往哪儿写 */
@Composable
private fun AddonFallback(addon: AddonManifest, onBack: () -> Unit) {
	Column(Modifier.fillMaxSize()) {
		PageHeader(title = addon.name, subtitle = addon.summary, onBack = onBack)
		Column(Modifier.padding(16.dp)) {
			Text("这个功能还没写页面。", fontSize = 14.sp, fontWeight = FontWeight.Medium)
			Spacer(Modifier.height(8.dp))
			Text(
				"让 Agent写 " + addon.dir.absolutePath + "/pages.json，格式：\n" +
					"{\"start\":\"main\",\"pages\":{\"main\":{\"title\":\"标题\",\"blocks\":[" +
					"{\"type\":\"text\",\"text\":\"说明\"}," +
					"{\"type\":\"button\",\"text\":\"按钮\",\"action\":\"shell\",\"value\":\"ls /sdcard\"}]}}}",
				fontSize = 11.5.sp,
				fontFamily = FontFamily.Monospace,
				color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
			)
		}
	}
}

/**
 *动作分发。
 *
 *四种：shell（跑命令）、open（开应用/链接/文件）、http（拉一段文本）、
 * goto（跳页）。「close」是悬浮窗那边才有的，页面里不需要。
 */
private fun runAddonAction(
	scope: CoroutineScope,
	state: AddonPageState,
	index: Int,
	action: String,
	rawValue: String,
	goto: (String) -> Unit,
) {
	//把 {input_id}换成输入框里的值 —— Agent写的命令里能引用
	var value = rawValue
	state.inputs.forEach { (k, v) -> value = value.replace("{" + k + "}", v) }

	val ctx = AddonRuntime.ctx

	when (action) {
		"goto" -> goto(value)

		"shell" -> {
			if (ctx == null) {
				state.out[index] = "（没有上下文，跑不了）"
				return
			}
			state.out[index] = "…"
			scope.launch {
				val r: ToolResult = runCatching { AgentRunner.shell(ctx, AgentStore.env, value) }
					.getOrElse { ToolResult(false, it.message ?: "执行失败") }
				state.out[index] = if (r.ok) r.output.take(4000) else "出错了：" + r.output.take(2000)
			}
		}

		"open" -> {
			if (ctx == null) {
				state.out[index] = "（没有上下文，打不开）"
				return
			}
			val r = AgentAddonOps.openBlocking(ctx, value)
			state.out[index] = if (r.ok) "打开好了" else r.output
		}

		"http" -> {
			if (ctx == null) {
				state.out[index] = "（没有上下文）"
				return
			}
			state.out[index] = "…"
			scope.launch {
				val r = runCatching { AgentAddonOps.httpGet(value) }
					.getOrElse { "拉不到：" + (it.message ?: "") }
				state.out[index] = r.take(4000)
			}
		}

		else -> state.out[index] = "（这个动作还不认识：" + action + "）"
	}
}
