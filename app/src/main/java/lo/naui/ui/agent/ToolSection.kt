package lo.naui.ui.agent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lo.naui.agent.ToolPrefs
import lo.naui.ui.component.IndicatorSwitch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「这个会话能用哪些工具」。
 *
 * ##为什么按会话挑
 *
 *会话 A想让它放开手跑 shell、会话 B只想让它读读文件 ——两件事本来就该能同时存在。
 *存储和"没挑过 =全开"的约定写在 [ToolPrefs]里。
 *
 * ##这张清单里也有它自己写的工具
 *
 * Agent用 `tool_new`写出来的那些会带个「（自己写的）」后缀列在这儿，
 *而且**当场就能看到**（[ToolPrefs.catalog]每次都重新读磁盘，不吃缓存）。
 */
@Composable
fun ToolSection(ctx: Context, convId: String?) {
	var expanded by remember { mutableStateOf(false) }
	//挑过的存在 SharedPreferences里，改完得让这一块重画
	var tick by remember { mutableStateOf(0) }

	val saved = run {
		tick
		ToolPrefs.enabled(ctx, convId)
	}
	val list = run {
		tick
		ToolPrefs.catalog(ctx)
	}

	Column(Modifier.fillMaxWidth()) {
		Row(
			Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(10.dp))
				.clickable { expanded = !expanded }
				.padding(vertical =6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text("这个会话能用的工具", fontSize =13.sp, fontWeight = FontWeight.Medium)
			Spacer(Modifier.weight(1f))
			Text(
				if (saved == null) "全部 " + list.size + "个"
				else saved.size.toString() + " / " + list.size,
				fontSize =11.5.sp,
				color = MiuixTheme.colorScheme.primary,
			)
			Text(
				if (expanded) "收起" else "展开",
				fontSize =11.5.sp,
				color = MiuixTheme.colorScheme.primary,
			)
		}

		Text(
			"挑过的只对**当前这个会话**生效，别的会话照旧；没挑过就是全开",
			fontSize =10.sp,
			color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
		)

		if (expanded) {
			Spacer(Modifier.height(8.dp))

			// 「全都打开」=把这份会话的记录整条删掉，退回默认的全开
			if (saved != null) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Pill("全都打开") {
						ToolPrefs.reset(ctx, convId)
						tick++
					}
				}
				Spacer(Modifier.height(8.dp))
			}

			list.forEach { (name, label, desc) ->
				val on = saved?.contains(name) ?: true
				Row(
					Modifier
						.fillMaxWidth()
						.padding(vertical =4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Column(Modifier.weight(1f)) {
						Text(
							label,
							fontSize =12.5.sp,
							color = if (on) MiuixTheme.colorScheme.onSurface
							else MiuixTheme.colorScheme.onSurfaceVariantSummary,
							maxLines =1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							if (desc.isBlank()) name else name + " · " + desc,
							fontSize =10.sp,
							color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
							maxLines =2,
							overflow = TextOverflow.Ellipsis,
						)
					}
					IndicatorSwitch(
						checked = on,
						onCheckedChange = { v ->
							ToolPrefs.setEnabled(ctx, convId, name, v)
							tick++
						},
					)
				}
			}
		}
	}
}

/**小圆角按钮（这一页别处也是这么画的） */
@Composable
private fun Pill(text: String, onClick: () -> Unit) {
	Box(
		Modifier
			.clip(RoundedCornerShape(50))
			.background(MiuixTheme.colorScheme.primary.copy(alpha =0.14f))
			.clickable { onClick() }
			.padding(horizontal =12.dp, vertical =6.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(text, fontSize =11.5.sp, color = MiuixTheme.colorScheme.primary)
	}
}
