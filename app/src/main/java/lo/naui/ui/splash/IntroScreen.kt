package lo.naui.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 *首次进入的绘制动画。
 *
 * ##为什么不用图
 *
 *整屏没有一个 bitmap：环、字母 N的三笔、八颗粒子、收尾那圈光，
 *全是 `Canvas`的绘制调用现画出来的。好处是**任何分辨率都锐**，
 *颜色跟着主题走（`MiuixTheme`的 primary），换主题不用重出一套图。
 *
 * ##怎么做到「流畅」
 *
 *逐帧驱动只有**一个** `Float`：`t`（秒）。
 *
 *关键在**谁读 t**：
 * - `Canvas`的绘制 lambda里读 →只让**绘制阶段**失效，不重组；
 * -文字用 `Modifier.graphicsLayer {}`，它的 block跑在**布局阶段**，也只失效那一层。
 *
 *也就是说动画全程**一次重组都没有** ——每秒60帧全是重绘。
 *这是它能跟系统那个开屏动画掰手腕的原因：没有一帧花在重组整棵树上。
 *
 * ##时间轴
 *
 * ```
 *0.00 →0.75环从 -90°顺时针画出来（dec缓出），带一圈柔光
 *0.45 →1.15字母 N的三笔依次落笔，每笔晚0.17s
 *0.30 →1.55八颗粒子从外圈收进来，同时缓慢公转
 *1.00 →1.60字标 Nakour淡入 +上移
 *1.95 →2.50中心一圈主色光晕铺开，收尾
 * ```
 *
 *缓动用的是 Material的「强调减速」近似（起步快、尾巴长），
 *不是线性的 ——线性看着就"廉航"，这是跟 Google那种质感的差距所在。
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
	var t by remember { mutableFloatStateOf(0f) }

	val primary = MiuixTheme.colorScheme.primary
	val track = MiuixTheme.colorScheme.onSurfaceVariantSummary
	val surface = MiuixTheme.colorScheme.surface

	//逐帧：while(true)里读下一帧的时间戳，算出已过去的秒数
	LaunchedEffect(Unit) {
		val t0 = withFrameNanos { it }
		while (true) {
			val now = withFrameNanos { it }
			val sec = (now - t0) /1000000000f
			t = sec
			if (sec >= TOTAL) {
				break
			}
		}
		onDone()
	}

	Box(
		Modifier
			.fillMaxSize()
			.background(surface),
	) {
		Column(
			Modifier.fillMaxSize(),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Canvas(Modifier.size(200.dp)) {
				drawMark(t, primary, track)
			}
			Spacer(Modifier.height(20.dp))
			BasicText(
				"Nakour",
				modifier = Modifier.graphicsLayer {
					val p = decel(seg(1.0f,1.6f, t))
					alpha = p *0.9f
					translationY = (1f - p) *30f
				},
				style = TextStyle(
					color = track,
					fontSize =12.5.sp,
					letterSpacing =4.sp,
				),
			)
		}

		//收尾那圈光：单独一层全屏，不然会被上面那个200dp的框裁掉
		Canvas(Modifier.fillMaxSize()) {
			drawBloom(t, primary)
		}
	}
}

// ─────────────────────────时间轴 ─────────────────────────

private const val TOTAL =2.5f
private const val ZERO =0f
private const val ONE =1f

private val TAU = (PI *2.0).toFloat()

/**把 `t`在 `[from, to]`这一段里映射成0~1，两头都夹住 */
private fun seg(from: Float, to: Float, t: Float): Float {
	val span = to - from
	if (span <=0f) {
		return ONE
	}
	val p = (t - from) / span
	if (p <=0f) {
		return ZERO
	}
	if (p >=1f) {
		return ONE
	}
	return p
}

/** Material的「强调减速」近似：起步快、尾巴长 */
private fun decel(p: Float): Float {
	val x = p.coerceIn(0f,1f)
	val inv =1f - x
	return	1f - inv * inv * inv
}

/** smoothstep：两头都软，用来收尾 */
private fun smooth(p: Float): Float {
	val x = p.coerceIn(0f,1f)
	return x * x * (3f -2f * x)
}

// ─────────────────────────绘制 ─────────────────────────

/**主体：环 + N +粒子。画在一个正方形里，中心即圆心 */
private fun DrawScope.drawMark(t: Float, primary: Color, track: Color) {
	val cx = size.width /2f
	val cy = size.height /2f
	val r = size.minDimension *0.44f
	val sw = size.minDimension *0.022f
	val mid = Offset(cx, cy)

	//收尾时整体让一让，别跟那圈光打架
	val fade =1f - smooth(seg(1.9f,2.5f, t)) *0.75f

	//①底环：一直在，很淡
	drawCircle(
		color = track.copy(alpha =0.10f),
		radius = r,
		center = mid,
		style = Stroke(width = sw),
	)

	//②环上那一段"进度"，从正上方顺时针画出来
	val ring = decel(seg(0f,0.75f, t))
	if (ring >0f) {
		drawArc(
			color = primary,
			startAngle = -90f,
			sweepAngle =360f * ring,
			useCenter = false,
			topLeft = Offset(cx - r, cy - r),
			size = Size(r *2f, r *2f),
			style = Stroke(width = sw, cap = StrokeCap.Round),
			alpha = fade,
		)
	}

	//③柔光：环后面垫一层，让主色"发光"
	val glow = seg(0.25f,1.1f, t)
	if (glow >0f) {
		val gr = r *1.9f
		drawCircle(
			brush = Brush.radialGradient(
				colors = listOf(primary.copy(alpha =0.20f * glow * fade), Color.Transparent),
				center = mid,
				radius = gr,
			),
			radius = gr,
			center = mid,
		)
	}

	//④字母 N：三笔，一笔比一笔晚
	val s = r *0.52f
	val spec = Stroke(width = sw *1.15f, cap = StrokeCap.Round)
	val n1 = decel(seg(0.45f,0.80f, t))
	val n2 = decel(seg(0.62f,1.00f, t))
	val n3 = decel(seg(0.78f,1.15f, t))
	trimLine(Offset(cx - s, cy - s), Offset(cx - s, cy + s), n1, primary, spec, fade)
	trimLine(Offset(cx - s, cy - s), Offset(cx + s, cy + s), n2, primary, spec, fade)
	trimLine(Offset(cx + s, cy - s), Offset(cx + s, cy + s), n3, primary, spec, fade)

	//⑤八颗粒子：从外圈收进来，边收边转
	val dots = seg(0.30f,1.55f, t)
	if (dots >0f) {
		repeat(8) { i ->
			val base = (i.toFloat() /8f) * TAU
			val dt = decel(seg(0.30f + i *0.055f,0.95f + i *0.055f, t))
			val ang = base + t *0.6f
			val dist = r * (1.55f - dt *0.55f)
			val x = cx + cos(ang) * dist
			val y = cy + sin(ang) * dist
			drawCircle(
				color = primary.copy(alpha = dt *0.85f * fade),
				radius = sw *0.55f,
				center = Offset(x, y),
			)
		}
	}
}

/**收尾：中心一圈主色光晕铺满整屏，然后交棒给下一页 */
private fun DrawScope.drawBloom(t: Float, primary: Color) {
	val rev = smooth(seg(1.95f,2.5f, t))
	if (rev <=0f) {
		return
	}
	val big = size.maxDimension *0.95f
	val mid = Offset(size.width /2f, size.height /2f)
	drawCircle(
		brush = Brush.radialGradient(
			colors = listOf(primary.copy(alpha =0.32f * rev), Color.Transparent),
			center = mid,
			radius = big,
		),
		radius = big,
		center = mid,
	)
}

/**
 *沿一条线段画到 `p`那么多（0 =没画，1 =整条）。
 *
 *用 `PathMeasure`量出路长再取一段 ——这就是各家动画库里 `trimPath`的底子，
 *比"画两条短线"精确，也不会在圆头笔帽那儿的长度算错。
 */
private fun DrawScope.trimLine(
	from: Offset,
	to: Offset,
	p: Float,
	color: Color,
	spec: Stroke,
	alpha: Float,
) {
	if (p <=0f) {
		return
	}
	val path = Path()
	path.moveTo(from.x, from.y)
	path.lineTo(to.x, to.y)
	val out = Path()
	if (p >=1f) {
		out.addPath(path)
	} else {
		val pm = PathMeasure()
		pm.setPath(path, false)
		pm.getSegment(0f, pm.length * p, out, true)
	}
	drawPath(path = out, color = color, alpha = alpha, style = spec)
}
