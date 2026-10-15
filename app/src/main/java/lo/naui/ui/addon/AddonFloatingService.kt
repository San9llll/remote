package lo.naui.ui.addon

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.abs

/**
 *把 Context留一份给渲染器用。
 *
 *为什么要有这么个土办法：渲染器里那些按钮回调是 Compose的 lambda，
 *拿不到 Context的活口。与其给每个块都往下传一遍 ctx，不如进来那一刻存一份 ——
 * applicationContext，不会漏 Activity。
 */
object AddonRuntime {
	@Volatile private var app: Context? = null

	val ctx: Context? get() = app

	fun attach(c: Context?) {
		if (c != null) app = c.applicationContext
	}

	/**有没有悬浮窗权限 */
	fun canOverlay(c: Context): Boolean = runCatching {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(c) else true
	}.getOrDefault(false)

	/**现在哪个功能飘在屏幕上（空 =没有） */
	var shown by mutableStateOf("")
		private set

	fun markShown(id: String) { shown = id }
	fun markClosed(id: String) { if (shown == id) shown = "" }
}

/**
 *本地功能的**悬浮窗**。
 *
 *用户1.03.0点名要「悬浮窗」：Agent写一个功能，它不光出现在功能页里，
 *还能飘在**别的应用上面**。
 *
 * ##两种形态
 *
 *1. **小卡片**（默认，没有 edge字段时）：飘在屏幕上，按住拖着走，点 ×关掉。
 *2. **贴边侧边栏**（`"edge":"right"`或 `"left"`）：像系统自带的侧边栏那样
 *贴在屏幕边上 ——平时只剩一条细柄，点一下（或往屏幕中间拖）面板滑出来；
 *点细柄、点面板上的「收起」滑回去。
 *还可以给 `panelWidth`（面板宽，dp，默认210）/ `handleWidth`（细柄宽，dp，默认20）。
 *
 *怎么实现的：窗口平时只有细柄那么宽（就贴着屏幕边那一条），要拉开的时候才把窗口撑到「面板 +细柄」，再让面板从边上滑进来 ——滑的始终是同一块 View，不是两套窗口。
	 *
	 *细柄那一小块还得跟系统的「侧滑返回」抢地盘，所以顺手把它声明成手势排除区
	 *（`systemGestureExclusionRects`），从边上往回划才是往回划。
 *
 * ##为什么用 View不用 Compose
 *
 * Compose的 ViewTreeLifecycleOwner / SavedStateRegistry得自己搭一套，
 *在 Service里搞这一套（还不一定稳）不值当 ——而这个窗口要显示的东西很少：
 *一个标题、一块文本、一排按钮。那点布局用 LinearLayout几分钟写完，还不怕生命周期。
 *
 * ##窗口长什么样也是 Agent写的
 *
 * `addons/<id>/ui/welcome.json`（没有就退回 pages.json里 kind=window那页）：
 *
 * ```json
 * {"title":"负载","edge":"right","source":"cat /proc/loadavg","interval":5,
 * "buttons":[{"text":"收起","action":"collapse"}]}
 * ```
 *
 * `source`每 interval秒跑一次，输出贴在上面 —— Agent不碰 App也能做个监控器。
 * `buttons`里的 action支持 close / collapse / expand / shell / open。
 */
class AddonFloatingService : Service() {

	private var wm: WindowManager? = null
	private var root: View? = null
	private var body: TextView? = null
	private var panel: View? = null
	private var spec: JSONObject? = null
	private var params: WindowManager.LayoutParams? = null
	private var anim: ValueAnimator? = null
	private var id: String = ""
	private var runner: Thread? = null

	//贴边（edge）模式的几何：屏幕宽、面板宽、细柄宽
	private var edge = ""
	private var panelPx =0
	private var handlePx =0
	private var screenW =0
	private var opened = false

	@Volatile private var alive = true

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		AddonRuntime.attach(this)
		startForegroundIfPossible()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_SHOW -> {
				val newId = intent.getStringExtra(EXTRA_ID).orEmpty()
				if (newId.isBlank()) { stopSelf(); return START_NOT_STICKY }
				//每次都重建：这样 Agent改完 welcome.json再喊一次 addon_window，
				//窗口就是新样子（换了贴边、改了宽度都当场生效）
				removeWindow()
				id = newId
				spec = runCatching { Addons.windowSpec(this, newId) }.getOrNull()
				showWindow()
			}
			ACTION_HIDE -> { stopSelf(); return START_NOT_STICKY }
		}
		return START_STICKY
	}

	/* ----------------窗口 ---------------- */

	private fun showWindow() {
		val s = spec ?: return
		val w = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		wm = w

		val dm = resources.displayMetrics
		screenW = dm.widthPixels
		val want = s.optString("edge", "").trim().lowercase()
		edge = if (want == "left" || want == "right") want else ""
		val capPx = screenW *72 /100
		val wantPx = (s.optInt("panelWidth",210).coerceIn(150,300) * dm.density).toInt()
		panelPx = if (wantPx < capPx) wantPx else capPx
		handlePx = (s.optInt("handleWidth",20).coerceIn(10,34) * dm.density).toInt()

		root = if (edge.isEmpty()) plainCard(s) else edgeCard(s)

		val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		else
			@Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

		val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
			WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

		val lp = if (edge.isEmpty()) {
			WindowManager.LayoutParams(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				type, flags, PixelFormat.TRANSLUCENT,
			).apply {
				gravity = Gravity.TOP or Gravity.START
				x = dp(16)
				y = dp(120)
			}
		} else {
			opened = false
			WindowManager.LayoutParams(
				handlePx,
				WindowManager.LayoutParams.MATCH_PARENT,
				type, flags, PixelFormat.TRANSLUCENT,
			).apply {
				gravity = Gravity.TOP or Gravity.START
				x = if (edge == "right") screenW - handlePx else (0)
				y = statusBarPx()
			}
		}
		params = lp

		runCatching { wm?.addView(root, lp) }
		AddonRuntime.markShown(id)
		startTicking()
	}

	/**默认形态：一张飘着的小卡片 */
	private fun plainCard(s: JSONObject): View {
		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(14), dp(12), dp(14), dp(12))
			background = GradientDrawable().apply {
				cornerRadius = dp(16).toFloat()
				setColor(Color.argb(216,24,26,32))
				setStroke(dp(1), Color.argb(48,255,255,255))
			}
		}

		val head = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}
		head.addView(TextView(this).apply {
			text = titleOf(s)
			setTextColor(Color.WHITE)
			textSize =13f
		}, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,1f))
		head.addView(TextView(this).apply {
			text = "×"
			setTextColor(Color.argb(180,255,255,255))
			textSize =16f
			setPadding(dp(10),0, dp(4),0)
			setOnClickListener {
				runCatching { lo.naui.agent.AgentRunner.shellBlocking(this@AddonFloatingService, s.optString("onClose", "")) }
				stopSelf()
			}
		})
		card.addView(head)

		fillContent(s, card)

		var downX =0f
		var downY =0f
		var startX =0
		var startY =0
		card.setOnTouchListener { _, e ->
			when (e.action) {
				MotionEvent.ACTION_DOWN -> {
					downX = e.rawX; downY = e.rawY
					startX = params?.x ?: (0)
					startY = params?.y ?: (0)
					false
				}
				MotionEvent.ACTION_MOVE -> {
					params?.let {
						it.x = startX + (e.rawX - downX).toInt()
						it.y = startY + (e.rawY - downY).toInt()
						runCatching { wm?.updateViewLayout(root, it) }
					}
					true
				}
				else -> false
			}
		}
		return card
	}

	/**贴边形态：[细柄][面板]（贴右边）或者 [面板][细柄]（贴左边） */
	private fun edgeCard(s: JSONObject): View {
		val isRight = edge == "right"
		val r = dp(18).toFloat()
		//四角圆弧的顺序：左上、右上、右下、左下
		val radii = if (isRight)
			floatArrayOf(r, r,0f,0f,0f,0f, r, r)
		else
			floatArrayOf(0f,0f, r, r, r, r,0f,0f)

		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(14), dp(16), dp(14), dp(14))
			background = GradientDrawable().apply {
				cornerRadii = radii
				setColor(Color.argb(236,20,22,28))
				setStroke(dp(1), Color.argb(40,255,255,255))
			}
		}
		panel = card

		val head = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}
		head.addView(TextView(this).apply {
			text = titleOf(s)
			setTextColor(Color.WHITE)
			textSize =14f
		}, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,1f))
		head.addView(TextView(this).apply {
			text = if (isRight) "›" else "‹"
			setTextColor(Color.argb(170,255,255,255))
			textSize =18f
			setPadding(dp(12), dp(2), dp(4), dp(2))
			setOnClickListener { slideTo(false) }
		})
		card.addView(head)
		attachDrag(head)

		val col = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(10),0, dp(6))
		}
		fillContent(s, col)

		val scroll = ScrollView(this).apply {
			isVerticalScrollBarEnabled = false
			overScrollMode = View.OVER_SCROLL_NEVER
			addView(col)
		}
		card.addView(
			scroll,
			LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f),
		)

		//细柄：整条高度都能摸（透明的），看得见的是中间那一小截药丸
		val grip = FrameLayout(this)
		val pillW = if (handlePx /3 > dp(4)) handlePx /3 else dp(4)
		grip.addView(
			View(this).apply {
				background = GradientDrawable().apply {
					cornerRadius = dp(5).toFloat()
					setColor(Color.argb(150,130,170,240))
				}
			},
			FrameLayout.LayoutParams(pillW, dp(88), Gravity.CENTER),
		)
		grip.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			//细柄正好落在系统「侧滑返回」的热区里，声明成手势排除区，
			//从边上往回划才是往回划
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				runCatching {
					val band = dp(150)
					val top = (v.height - band) /2
					v.systemGestureExclusionRects =
						listOf(android.graphics.Rect(0, top, v.width, top + band))
				}
			}
		}
		attachGrip(grip)

		val frame = FrameLayout(this)
		val pLp = FrameLayout.LayoutParams(
			panelPx,
			FrameLayout.LayoutParams.MATCH_PARENT,
			if (isRight) Gravity.END else Gravity.START,
		)
		if (isRight) pLp.rightMargin = handlePx else pLp.leftMargin = handlePx
		frame.addView(card, pLp)
		frame.addView(
			grip,
			FrameLayout.LayoutParams(
				handlePx,
				FrameLayout.LayoutParams.MATCH_PARENT,
				if (isRight) Gravity.END else Gravity.START,
			),
		)
		//细柄永远画在面板上头 ——推拉的时候它得留在最外头
		grip.translationZ = dp(6).toFloat()
		card.translationX = hiddenShift()
		return frame
	}

	/**标题 +正文 +按钮，两种形态共用这一段 */
	private fun fillContent(s: JSONObject, into: LinearLayout) {
		body = TextView(this).apply {
			setTextColor(Color.argb(226,255,255,255))
			textSize =11.5f
			typeface = android.graphics.Typeface.MONOSPACE
			text = s.optString("placeholder", "…")
			setLineSpacing(0f,1.12f)
		}
		into.addView(body)

		val btns = s.optJSONArray("buttons")
		if (btns != null && btns.length() >0) {
			val row = LinearLayout(this).apply {
				orientation = LinearLayout.HORIZONTAL
				setPadding(0, dp(10),0,0)
			}
			for (i in (0 until btns.length())) {
				val b = btns.optJSONObject(i) ?: continue
				row.addView(TextView(this).apply {
					text = b.optString("text", "按钮")
					setTextColor(Color.argb(255,140,190,255))
					textSize =12f
					setPadding(dp(12), dp(4), dp(12), dp(4))
					setOnClickListener { onButton(b.optString("action", "shell"), b.optString("value", "")) }
				})
			}
			into.addView(row)
		}
	}

	private fun titleOf(s: JSONObject): String =
		s.optString("title", "").takeIf { it.isNotBlank() }
			?: (Addons.read(this, id)?.name ?: "本地功能")

/* ----------------贴边的推拉 ---------------- */

	/**面板藏在边上时的位移量（贴右边是正的，贴左边是负的） */
	private fun hiddenShift(): Float = if (edge == "right") panelPx.toFloat() else -panelPx.toFloat()

	/**
	 *换窗口的外框。
	 *
	 *为什么非得换宽度：窗口的触摸区就是它那块矩形。收着的时候如果还留一条
	 *（panelPx + handlePx）宽的窗口挂在屏幕上，那底下的按钮就全点不动了 ——
	 *所以收起来时窗口只有细柄那么宽。
	 */
	private fun applyFrame(expanded: Boolean) {
		if (edge.isEmpty()) return
		val lp = params ?: return
		val v = root ?: return
		lp.width = if (expanded) panelPx + handlePx else handlePx
		lp.x = if (edge == "right")
			(if (expanded) screenW - panelPx - handlePx else screenW - handlePx)
		else
			(0)
		runCatching { wm?.updateViewLayout(v, lp) }
	}

	/**滑出来 /滑回去 */
	private fun slideTo(open: Boolean, animate: Boolean = true) {
		if (edge.isEmpty() || params == null) return
		anim?.cancel()
		opened = open
		if (open) {
			//窗口先撑开，面板这会儿还藏在边上，再把它推进来
			applyFrame(true)
			if (!animate) {
				panel?.translationX =0f
				return
			}
			val a = ValueAnimator.ofFloat(panel?.translationX ?: (0f),0f)
			a.duration =200L
			a.addUpdateListener { va -> panel?.translationX = va.animatedValue as Float }
			anim = a
			a.start()
		} else {
			val hidden = hiddenShift()
			if (!animate) {
				panel?.translationX = hidden
				applyFrame(false)
				return
			}
			val a = ValueAnimator.ofFloat(panel?.translationX ?: (0f), hidden)
			a.duration =200L
			a.addUpdateListener { va -> panel?.translationX = va.animatedValue as Float }
			a.addListener(object : android.animation.AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: android.animation.Animator) {
					//推到最边上了，窗口才收成细柄
					if (!opened) applyFrame(false)
				}
			})
			anim = a
			a.start()
		}
	}

	/**
	 *细柄上的手势：点一下开/关，横着拖就跟着走。
	 *
	 *只认横着走，竖着划不抢（不然贴边那儿的滚动会被它吃掉）。
	 */
	private fun attachGrip(g: View) {
		var downRaw =0f
		var moved = false
		g.setOnTouchListener { _, e ->
			when (e.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					downRaw = e.rawX
					moved = false
					true
				}
				MotionEvent.ACTION_MOVE -> {
					val dx = e.rawX - downRaw
					if (abs(dx) <= dp(3)) return@setOnTouchListener true
					if (!moved) {
						moved = true
						//一动手就把窗口撑开，后面只动面板的位移 ——
						//不然每一帧都要改窗口尺寸，卡
						if (!opened) {
							opened = true
							applyFrame(true)
						}
						panel?.translationX = hiddenShift()
					}
					anim?.cancel()
					val hidden = hiddenShift()
					val dir = if (edge == "right") dx else -dx
					panel?.translationX = (hidden + dir).coerceIn(0f, hidden)
					true
				}
				else -> {
					val hidden = hiddenShift()
					val at = panel?.translationX ?: hidden
					if (!moved) slideTo(!opened) else slideTo(at < hidden /2f)
					true
				}
			}
		}
	}

	/**面板的标题栏也能往边上推 ——不用去够那颗小箭头 */
	private fun attachDrag(header: View) {
		var downRaw =0f
		var moved = false
		header.setOnTouchListener { _, e ->
			when (e.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					downRaw = e.rawX
					moved = false
					true
				}
				MotionEvent.ACTION_MOVE -> {
					val dir = if (edge == "right") e.rawX - downRaw else downRaw - e.rawX
					if (dir <= dp(4)) return@setOnTouchListener false
					moved = true
					anim?.cancel()
					panel?.translationX = dir.coerceIn(0f, hiddenShift())
					true
				}
				else -> {
					if (!moved) return@setOnTouchListener false
					val hidden = hiddenShift()
					val at = panel?.translationX ?: hidden
					slideTo(!(at > hidden /2f))
					true
				}
			}
		}
	}

	
/**
		 *定时刷那块文本。
	 *
	 *为什么是裸 Thread不是协程：Service里没有现成的 scope，
	 *而这件事就是「睡一会儿、跑条命令、贴上去」——一个循环够了。
	 */
	private fun startTicking() {
		val s = spec ?: return
		val cmd = s.optString("source", "")
		if (cmd.isBlank()) return
		val every = (s.optInt("interval",5).coerceIn(1,600)) *1000L

		alive = true
		runner = Thread {
			while (alive) {
				val out = runCatching {
					lo.naui.agent.AgentRunner.shellBlocking(this, cmd)
				}.getOrDefault("（跑不出来）")
				val text = Addons.lines(out, s.optInt("lines",12))
					.joinToString("\n")
					.ifBlank { "（没有输出）" }
				body?.post { body?.text = text }
				try {
					Thread.sleep(every)
				} catch (e: InterruptedException) {
					return@Thread
				}
			}
		}.apply {
			isDaemon = true
			start()
		}
	}

	private fun onButton(action: String, value: String) {
		when (action) {
			"close" -> stopSelf()
			//贴边模式专用：收起来（窗口还在，细柄还在）/再滑出来
			"collapse" -> slideTo(false)
			"expand" -> slideTo(true)
			"open" -> runCatching {
				startActivity(
					Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				)
			}
			else -> {
				Thread {
					val out = runCatching {
						lo.naui.agent.AgentRunner.shellBlocking(this, value)
					}.getOrDefault("（跑不出来）")
					body?.post { body?.text = Addons.lines(out,20).joinToString("\n").ifBlank { "（没有输出）" } }
				}.apply { isDaemon = true }.start()
			}
		}
	}

	private fun removeWindow() {
		alive = false
		anim?.cancel()
		anim = null
		runner?.interrupt()
		runner = null
		runCatching { root?.let { wm?.removeView(it) } }
		root = null
		body = null
		panel = null
		if (id.isNotBlank()) AddonRuntime.markClosed(id)
	}

	override fun onDestroy() {
		removeWindow()
		super.onDestroy()
	}

	/* ----------------通知 ---------------- */

	private fun startForegroundIfPossible() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		runCatching {
			val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			if (nm.getNotificationChannel(CHANNEL) == null) {
				nm.createNotificationChannel(
					NotificationChannel(CHANNEL, "本地功能悬浮窗", NotificationManager.IMPORTANCE_MIN)
				)
			}
			val open = Intent(this, lo.naui.MainActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			val pi = PendingIntent.getActivity(
				this,0, open,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
			)
			val n: Notification = Notification.Builder(this, CHANNEL)
				.setContentTitle("本地功能在跑")
				.setContentText("有一个自己写的小功能飘在屏幕上")
				.setSmallIcon(android.R.drawable.stat_notify_sync)
				.setContentIntent(pi)
				.setOngoing(true)
				.build()
			startForeground(NOTIFY_ID, n)
		}
	}

	/**状态栏高度。面板从头一条开始铺，不躲开就会被状态栏盖住标题 */
	private fun statusBarPx(): Int = runCatching {
		val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
		if (rid >0) resources.getDimensionPixelSize(rid) else (0)
	}.getOrDefault(0)

	private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

	companion object {
		private const val CHANNEL = "nakour_addon_window"
		private const val NOTIFY_ID =0x4E42

		const val ACTION_SHOW = "lo.naui.addon.SHOW"
		const val ACTION_HIDE = "lo.naui.addon.HIDE"
		private const val EXTRA_ID = "id"

		/**把这个功能的悬浮窗打开（没权限就先不去） */
		fun show(ctx: Context, id: String) {
			if (!AddonRuntime.canOverlay(ctx)) return
			val i = Intent(ctx, AddonFloatingService::class.java).apply {
				action = ACTION_SHOW
				putExtra(EXTRA_ID, id)
			}
			runCatching {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
				else ctx.startService(i)
			}
		}

		fun hide(ctx: Context) {
			runCatching { ctx.stopService(Intent(ctx, AddonFloatingService::class.java)) }
		}

		/**要悬浮窗权限的那个系统页 */
		fun overlaySettingsIntent(ctx: Context): Intent = Intent(
			Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
			android.net.Uri.parse("package:" + ctx.packageName),
		).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
	}
}
