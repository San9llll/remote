package lo.naui.sys

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * 流体云 —— 把通知抬到状态栏那块小胶囊 / 灵动岛上。
 *
 * 说明白点：**这不是第三方能直接调的 API**。
 * 应用能做的是发一个**带了特定 extras 的通知**，让系统自己判断要不要抬上去。
 *
 * 目前接了两家：
 *
 * | 系统 | 叫法 | 认的字段 |
 * |---|---|---|
 * | MIUI / 澎湃 | 焦点通知 / 流体云 | `miui.focus.*` |
 * | ColorOS（OPPO / 一加 / realme） | 流体云 / 实时通知 | `oppo.focus.*` |
 *
 * 两套字段一起塞进去就行 —— 各家只认自己那几个，多余的会被忽略，
 * 不是自家系统的话就是个普通通知，不会出错。
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

    /** 是不是 ColorOS 系（OPPO / 一加 / realme） */
    fun isColorOs(): Boolean = runCatching {
        val brand = android.os.Build.MANUFACTURER.lowercase()
        val brandHit = brand.contains("oppo") || brand.contains("oneplus") ||
            brand.contains("realme") || brand.contains("oplus")
        // 系统属性也能佐证
        val propHit = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            val v = get.invoke(null, "ro.build.version.opporom") as? String
            // ⚠️ 必须写 return@runCatching —— Kotlin 的换行不算语句结束，
            // 直接跟一个 `!v...` 会被当成 `as? String !v` 一起解析，语法错
            return@runCatching !v.isNullOrBlank()
        }.getOrDefault(false)
        brandHit || propHit
    }.getOrDefault(false)

    /** 这台机器上，这套东西到底有没有用 */
    fun supported(): Boolean = isMiui() || isColorOs()

    /** 给设置页显示的一句话 */
    fun systemLabel(): String = when {
        isMiui() -> "小米 / 澎湃"
        isColorOs() -> "ColorOS（OPPO / 一加 / realme）"
        else -> ""
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
            // ---- 小米 / 澎湃 ----
            putBoolean("miui.focus.param", true)
            putString("miui.focus.pic_title", title)
            putString("miui.focus.pic_content", content)
            putBoolean("miui.focus.ticker", true)
            putString("miui.focus.ticker.content", content)

            // ---- ColorOS（OPPO / 一加 / realme）----
            // 两套一起塞：各家只认自己那几个，多余的会被忽略
            putBoolean("oppo.focus.param", true)
            putString("oppo.focus.title", title)
            putString("oppo.focus.content", content)
            putBoolean("oppo.focus.ticker", true)
            putString("oppo.focus.ticker.content", content)
            // ColorOS 认这个当"实时通知"的标记
            putBoolean("android.allowDuringSetup", false)
            putString("oppo.notification.extra", content)
        }
        return runCatching {
            builder.setExtras(extras)
        }.getOrDefault(builder)
    }

    /**
     * 发一条测试通知 —— 设置页那个按钮用。
     *
     * 存在的意义：流体云"没显示"的时候，分不清是**代码没跑**还是**系统不认**。
     * 点一下这个，立刻发一条出去：
     *   - 能看到 → 说明字段和渠道都对，之前是别的地方没触发
     *   - 看不到 → 那就是系统没给抬（可能要白名单），跟代码无关
     */
    fun sendTest(ctx: Context) {
        // 测试不受开关限制，不然关着开关就没法验了
        val save = enabled
        enabled = true
        notifyFocus(ctx, 0x4F50, "Nakour 测试", "能看见的话说明字段和渠道都对")
        enabled = save
    }

    /** 给设置页显示的一句诊断 */
    fun diagnose(ctx: Context): String {
        init(ctx)
        val sys = systemLabel()
        return when {
            sys.isBlank() -> "这台机器不是小米 / ColorOS，系统不认这套字段"
            !enabled -> "开关没开"
            else -> "系统是 " + sys + "，字段已带上；点「测试一下」看能不能抬上去"
        }
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
