package lo.naui.sys

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * 流体云（小米的"焦点通知"）。
 *
 * 说明白点：**流体云不是第三方能直接调的 API**。
 * 应用能做的是发一个**带了特定 extras 的通知**，MIUI 自己判断要不要把它
 * 抬到灵动岛 / 状态栏胶囊那块区域去显示。
 *
 * 所以这里做的就是"把该带的字段带上"：
 *
 * ```
 * miui.focus.param        true        声明这是焦点通知
 * miui.focus.pic_icon     图标资源名
 * miui.focus.pic_title    标题
 * miui.focus.pic_content  内容
 * miui.focus.ticker       true        允许走"流光"那条样式
 * miui.focus.ticker.icons 流光小图标
 * miui.focus.ticker.content 流光文字
 * ```
 *
 * ⚠️ 两个前提，不是代码能解决的：
 *   1. 得是 MIUI / 澎湃（其它系统认不出这些字段，就是个普通通知）
 *   2. 应用**可能要进小米的白名单**才抬得上去，普通应用有时不生效
 *
 * 所以它是"尽力而为" —— 抬上去了算赚，没抬也不会出错。
 */
object FluidCloud {

    private var sp: android.content.SharedPreferences? = null

    /** 可观察 —— 设置页那个开关要跟着它重画 */
    var enabled by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_fluid", Context.MODE_PRIVATE)
        sp = p
        enabled = p.getBoolean("enabled", false)
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        enabled = v
        sp?.edit()?.putBoolean("enabled", v)?.apply()
    }

    /** 是不是小米系（不是的话这些字段没人认） */
    fun isMiui(): Boolean = runCatching {
        val p = java.lang.System.getProperty("ro.miui.ui.version.name") ?: ""
        p.isNotBlank() ||
            android.os.Build.MANUFACTURER.equals("Xiaomi", true) ||
            android.os.Build.MANUFACTURER.equals("Redmi", true) ||
            android.os.Build.MANUFACTURER.equals("POCO", true)
    }.getOrDefault(false)

    /**
     * 把一个普通通知"加工"成焦点通知。
     *
     * 没开开关就直接返回原样 —— 调用方不用自己判断。
     */
    fun decorate(ctx: Context, builder: Notification.Builder, title: String, content: String): Notification.Builder {
        init(ctx)
        if (!enabled) return builder

        val extras = Bundle().apply {
            putBoolean("miui.focus.param", true)
            putString("miui.focus.pic_title", title)
            putString("miui.focus.pic_content", content)
            // 流光那条样式
            putBoolean("miui.focus.ticker", true)
            putString("miui.focus.ticker.content", content)
        }
        return runCatching {
            builder.setExtras(extras)
        }.getOrDefault(builder)
    }

    /** 单独发一条焦点通知（用于"任务跑完了"这种一次性提醒） */
    fun notifyFocus(ctx: Context, id: Int, title: String, content: String) {
        init(ctx)
        if (!enabled) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val chId = "nakour_fluid"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "流体云提醒", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "任务进度抬到状态栏那块小区域" }
                )
            }

            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, chId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(
                ctx, 0,
                android.content.Intent(ctx, lo.naui.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
            )

            b.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setAutoCancel(true)

            decorate(ctx, b, title, content)
            nm.notify(id, b.build())
        }
    }
}
