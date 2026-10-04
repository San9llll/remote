package lo.naui.sys

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import lo.naui.agent.AgentTaskStore

/**
 * 悬浮球。
 *
 * 为什么要有它：Agent 的任务是能后台跑的，但用户切走了就看不见进度了 ——
 * 通知栏那行字信息太少。所以给一个能飘在别的应用上面的小球：
 * 有任务在跑就亮绿灯、还能看到"第几轮/在干嘛"，点一下回 App，拖着能挪位置。
 *
 * 权限是 `SYSTEM_ALERT_WINDOW`（"显示在其他应用上层"），
 * 得用户手动去系统设置里开，没法运行时申请。
 *
 * 实现上**故意不用 ComposeView** —— 往 WindowManager 塞 Compose 要把
 * ViewTree 的 Lifecycle / ViewModelStore / SavedState 三个 owner 全挂上，
 * 少一个就抛 "ViewTreeLifecycleOwner not found"。这里就一个小球，
 * 用普通 View 手搭省事也不会出那类幺蛾子。
 */
object FloatingBall {

    fun canShow(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
    }.getOrDefault(false)

    /** 跳去开权限 */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun start(ctx: Context) {
        if (!canShow(ctx)) return
        runCatching {
            val i = Intent(ctx, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, FloatingBallService::class.java)) }
    }

    private var sp: android.content.SharedPreferences? = null

    /** 可观察 —— 设置页那个开关要跟着它重画 */
    var enabled by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_float", Context.MODE_PRIVATE)
        sp = p
        enabled = p.getBoolean("enabled", false)
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        enabled = v
        sp?.edit()?.putBoolean("enabled", v)?.apply()
        if (v) start(ctx) else stop(ctx)
    }
}

class FloatingBallService : Service() {

    private var ball: View? = null
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var dot: View
    private lateinit var label: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var ticking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFY_ID, buildNotification())
        AgentTaskStore.init(this)
        addBall()
        tick()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun addBall() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不抢焦点 —— 这样它飘着的时候底下的应用还能正常用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f)
            y = dp(160f)
        }

        // 整颗球就是一个横排：状态点 + 一行字
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(50f).toFloat()
                setColor(Color.argb(0xE0, 0x1B, 0x1B, 0x1F))
            }
        }

        dot = View(this).apply {
            val lp = LinearLayout.LayoutParams(dp(9f), dp(9f))
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9E9E9E"))
            }
        }
        row.addView(dot)

        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = dp(8f)
            layoutParams = lp
        }
        row.addView(label)

        // 拖动改坐标；没拖动就是点击（回 App）
        row.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (kotlin.math.abs(dx) > dp(6f) || kotlin.math.abs(dy) > dp(6f)) moved = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) openApp()
                }
                return true
            }
        })

        ball = row
        runCatching { wm.addView(row, params) }
    }

    /** 每 500ms 刷一次球上的内容（任务在跑就显示在干嘛） */
    private fun tick() {
        if (ticking) return
        ticking = true
        ui.post(object : Runnable {
            override fun run() {
                refresh()
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun refresh() {
        // 现在是按会话分开跑的 —— 球上显示"总共有几个在跑"
        val count = AgentTaskStore.runningCount
        val running = count > 0
        // 拿最后那个在跑的会话的进度当提示
        val hint = AgentTaskStore.state.progress

        runCatching {
            (dot.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (running) "#4CD964" else "#9E9E9E")
            )
            (ball?.background as? GradientDrawable)?.setColor(
                if (running) Color.argb(0xE6, 0x1B, 0x1B, 0x1F)
                else Color.argb(0xB3, 0x1B, 0x1B, 0x1F)
            )
            if (running) {
                label.visibility = View.VISIBLE
                val text = if (count > 1) count.toString() + " 个任务在跑" else "在跑…"
                if (label.text.toString() != text) label.text = text
            } else {
                label.visibility = View.GONE
            }
        }
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, lo.naui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    override fun onDestroy() {
        ticking = false
        ui.removeCallbacksAndMessages(null)
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        super.onDestroy()
    }

    /* ---------------- 通知（前台服务必须有） ---------------- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_MIN)
                        .apply { description = "让悬浮球一直飘着" }
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, lo.naui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        return b.setContentTitle("Nakour 悬浮球")
            .setContentText("飘着，点一下回来")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nakour_float"
        private const val NOTIFY_ID = 0x4F42
    }
}
