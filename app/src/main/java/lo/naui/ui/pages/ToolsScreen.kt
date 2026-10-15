package lo.naui.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.ui.addon.AddonFloatingService
import lo.naui.ui.addon.AddonRuntime
import lo.naui.ui.addon.AddonStore
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 *功能页。要加新工具就往下面再摞一张玻璃卡。
 *
 * 1.03.0起多了最后一栏「本地功能」——那是 Agent自己写出来的东西
 * （渲染器在 ui/addon/AddonRenderer.kt），存在
 * `Android/data/lo.naui/files/addons/`下，扫到几个就列几个，**不用重装 App**。
 *
 *左边那条工具侧边栏1.03.0撤了：它和卡片里这几项是**同一堆入口**，
 *一个页面上摆两层按钮，点哪个都行，看着还挤。
 */
@Composable
fun ToolsScreen(
	onOpenFiles: () -> Unit,
	onOpenTerminal: () -> Unit,
	onOpenShelf: () -> Unit,
	/**打开某个本地功能（Agent写的那些） */
	onOpenAddon: (String) -> Unit = {},
	backdrop: com.kyant.backdrop.Backdrop? = null,
) {
	val ctx = LocalContext.current
	//进来先扫一遍磁盘 —— Agent刚写完的功能这会儿就冒出来了
	LaunchedEffect(Unit) {
		AddonRuntime.attach(ctx)
		AddonStore.init(ctx)
		AddonStore.refresh()
	}

	Column(
		Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 14.dp),
	) {
		Spacer(Modifier.height(18.dp))
		Text("功能", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
		Spacer(Modifier.height(4.dp))
		Text(
			"能用最高权限去摸系统的地方 ·进子页后按音量上+下可钉到侧栏",
			fontSize = 13.sp,
			color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
		)
		Spacer(Modifier.height(20.dp))

		GlassCard(
			backdrop = backdrop,
			modifier = Modifier.fillMaxWidth(),
			contentPadding = 6.dp,
			enterIndex = 0,
		) {
			Column(Modifier.fillMaxWidth()) {
				ArrowPreference(
					title = "文件管理",
					summary = "双栏 ·能一直往上翻到 /，也可以自己加外部存储目录",
					onClick = onOpenFiles,
				)
				ArrowPreference(
					title = "终端",
					summary = "按当前最高权限执行命令（root / Shizuku /本地 sh）",
					onClick = onOpenTerminal,
				)
				ArrowPreference(
					title = "雫的书柜",
					summary = "写小说 · AI续写 ·分角色配音朗读",
					onClick = onOpenShelf,
				)
			}
		}

		// ----本地功能（Agent自己写的）----
		val addons = AddonStore.addons
		Spacer(Modifier.height(20.dp))
		Text("本地功能", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
		Spacer(Modifier.height(4.dp))
		Text(
			"Agent自己写出来的小功能，存在 addons/下，不用重装就能加",
			fontSize = 12.sp,
			color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
		)
		Spacer(Modifier.height(10.dp))

		if (addons.isEmpty()) {
			GlassCard(
				backdrop = backdrop,
				modifier = Modifier.fillMaxWidth(),
				contentPadding = 6.dp,
				enterIndex = 1,
			) {
				Column(Modifier.fillMaxWidth()) {
					ArrowPreference(
						title = "还没有本地功能",
						summary = "跟 Agent说一句「给我写个 XX功能」就行",
						onClick = {},
					)
				}
			}
		} else {
			addons.forEachIndexed { i, a ->
				GlassCard(
					backdrop = backdrop,
					modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
					contentPadding = 6.dp,
					enterIndex = i + 1,
				) {
					Column(Modifier.fillMaxWidth()) {
						ArrowPreference(
							title = a.name + (if (a.version.isBlank()) "" else " v" + a.version),
							summary = a.summary.ifBlank {
								if (a.isWindow) "悬浮窗" else "应用内页面"
							},
							onClick = { onOpenAddon(a.id) },
						)
						if (a.isWindow) {
							//悬浮窗单独一个按钮 ——它跟「打开页面」是两件事
							Row(
								Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Box(
									Modifier
										.clip(RoundedCornerShape(50))
										.background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
										.clickable {
											if (AddonRuntime.canOverlay(ctx)) {
												AddonFloatingService.show(ctx, a.id)
											} else {
												runCatching {
													ctx.startActivity(AddonFloatingService.overlaySettingsIntent(ctx))
												}
											}
										}
										.padding(horizontal = 14.dp, vertical = 7.dp),
									contentAlignment = Alignment.Center,
								) {
									Text(
										if (AddonRuntime.canOverlay(ctx)) "飘到屏幕上"
										else "先给悬浮窗权限",
										fontSize = 12.sp,
										color = MiuixTheme.colorScheme.primary,
									)
								}
							}
						}
					}
				}
			}
		}

		Spacer(Modifier.height(28.dp))
	}
}
