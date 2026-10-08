package lo.naui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege

/**
 * 设备动作类工具：剪贴板、通知、打开 app / 打开文件、截屏。
 *
 * ## 为什么要单独一类
 *
 * 这几个是"真在操作手机"最典型的动作，但**一条 shell 都干不成**：
 * 剪贴板是系统服务、通知要 NotificationManager、拉起 app 要 Intent。
 * 以前模型只能对它说"这个我做不到"，或者硬编一个 `am start` ——
 * 而 `am start` 在没 root 的普通权限下经常被 SELinux 挡，报回来的还是一句
 * 看不懂的 SecurityException，模型接着硬撞。
 *
 * ## 权限现实（照实说，不给模型虚假希望）
 *
 * · **剪贴板**：Android 10 起，只有**应用在前台**才读得到别人的内容；
 *   写不受限。读失败就照实回"要在前台才能读"。
 * · **截屏**：`screencap` 要 root 或 shell 身份，普通 app 权限做不到
 *   （正当路子是 MediaProjection，得用户每次点授权条，那是另一个量级的功能）。
 *   所以没特权时直接说清"需要 root"，不要让它以为重试有用。
 * · **通知**：Android 13+ 要 POST_NOTIFICATIONS 授权，没给就提示它去开。
 */
object AgentDeviceOps {

    private const val CH_ID = "agent_notice"

    /* ---------------- 剪贴板 ---------------- */

    fun clipboardRead(ctx: Context): ToolResult {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult(false, "这台机器上没有剪贴板服务")
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return ToolResult(true, "剪贴板是空的")
        val first = clip.getItemAt(0)
        val text = first.coerceToText(ctx)?.toString().orEmpty()
        val uri = first.uri
        return ToolResult(
            true,
            buildString {
                append("剪贴板内容（").append(text.length).append(" 字）：\n")
                append(text.take(4000))
                if (text.length > 4000) append("\n…（太长截断了）")
                if (uri != null) append("\n（还带一个内容 URI：").append(uri.toString()).append("）")
                if (clip.itemCount > 1) append("\n（一共 ").append(clip.itemCount).append(" 项，只取了第 1 项）")
            },
        )
    }

    fun clipboardWrite(ctx: Context, text: String, label: String): ToolResult {
        if (text.isEmpty()) return ToolResult(false, "要写的内容是空的。")
        val cm = ctx.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult(false, "这台机器上没有剪贴板服务")
        return runCatching {
            cm.setPrimaryClip(
                ClipData.newPlainText(label.ifBlank { "Nakour" }, text)
            )
            ToolResult(true, "写进剪贴板了（${text.length} 字）。⚠️ 有的系统会弹「已被复制」提示，这是正常的。")
        }.getOrElse { ToolResult(false, "剪贴板写入失败：" + (it.message ?: "")) }
    }

    /* ---------------- 通知 ---------------- */

    /**
     * 发一条通知。
     *
     * 不复用 Agent 任务那条渠道 —— 那条 IMPORTANCE_LOW，用户可能压根不看；
     * 单独一条 agent_notice，让模型自己定重要性。
     */
    fun notify(
        ctx: Context,
        title: String,
        text: String,
        important: Boolean,
        openAgent: Boolean,
    ): ToolResult = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return@runCatching ToolResult(false, "没有通知服务")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return@runCatching ToolResult(
                false,
                "通知权限没给。让用户在 系统设置 → 应用 → Nakour → 通知 里打开，再发。",
            )
        }
        val chId = if (important) "$CH_ID.high" else "$CH_ID"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val imp = if (important) NotificationManager.IMPORTANCE_DEFAULT
            else NotificationManager.IMPORTANCE_LOW
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "Agent 消息", imp)
                )
            }
        }
        val b = NotificationCompat.Builder(ctx, chId)
            .setContentTitle(title.ifBlank { "Nakour" })
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
        if (openAgent) {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                b.setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
        }
        nm.notify(("agent" + System.currentTimeMillis()).hashCode(), b.build())
        ToolResult(true, "通知发出去了：" + title + " — " + text)
    }.getOrElse { ToolResult(false, "发通知失败：" + (it.message ?: "")) }

    /* ---------------- 打开 app / 链接 / 文件 ---------------- */

    /**
     * target 可以是：包名 / http(s) 链接 / 文件绝对路径 / 设置项关键词。
     * 自己分辨，模型不用记四种写法。
     */
    fun open(ctx: Context, target: String): ToolResult {
        if (target.isBlank()) return ToolResult(false, "要打开什么？target 是空的。")
        val t = target.trim()
        return runCatching {
            when {
                t.startsWith("http://") || t.startsWith("https://") -> {
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(t)))
                    ToolResult(true, "用系统默认方式打开了链接：" + t)
                }

                t.startsWith("/") && File(t).exists() -> {
                    // ★ Android 7 起往外发 file:// Uri 会抛 FileUriExposedException，
                    //   必须过 FileProvider（项目里 UpdateChecker 装 APK 就是这么走的，
                    //   authority 用的也是同一个 .fileprovider）
                    val f = File(t)
                    val uri = runCatching {
                        androidx.core.content.FileProvider.getUriForFile(
                            ctx, ctx.packageName + ".fileprovider", f
                        )
                    }.getOrElse {
                        return@runCatching ToolResult(
                            false,
                            "这个文件没法共享出去（不在 file_paths.xml 配的范围内）：" + t +
                                "；要给人看就先复制到应用自己的目录里",
                        )
                    }
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, guessMime(t))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    launch(ctx, i)
                    ToolResult(true, "打开了文件：" + t)
                }

                else -> openPackage(ctx, t)
            }
        }.getOrElse { ToolResult(false, "打开失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun openPackage(ctx: Context, nameOrPkg: String): ToolResult {
        val pm = ctx.packageManager
        // 先当包名试，不行再按应用名模糊找一个
        val intent = pm.getLaunchIntentForPackage(nameOrPkg)
        if (intent != null) {
            launch(ctx, intent)
            return ToolResult(true, "打开了 " + nameOrPkg)
        }
        val byName = findAppIntent(ctx, nameOrPkg)
        if (byName != null) {
            launch(ctx, byName)
            return ToolResult(true, "按应用名找到了并打开：" + nameOrPkg)
        }
        return ToolResult(
            false,
            "既不是包名也没找到同名 app：" + nameOrPkg +
                "（用 list_apps 那个可以看到装了哪些）",
        )
    }

    /** 按显示名模糊找已装的 app */
    fun listApps(ctx: Context, keyword: String): ToolResult = runCatching {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { (pm.getApplicationLabel(it).toString()) to it.packageName }
            .sortedBy { it.first.lowercase(Locale.US) }
        val kw = keyword.trim().lowercase(Locale.US)
        val hit = if (kw.isEmpty()) apps else apps.filter {
            it.first.lowercase(Locale.US).contains(kw) || it.second.lowercase(Locale.US).contains(kw)
        }
        if (hit.isEmpty()) return@runCatching ToolResult(
            true,
            "（没有能启动的 app 匹配「" + keyword + "」。已装可启动的共 ${apps.size} 个，不带 keyword 再问一次能看全表）",
        )
        val sb = StringBuilder("可启动应用 " + hit.size + " 个：\n")
        hit.take(80).forEach { sb.append(it.first).append("  →  ").append(it.second).append('\n') }
        if (hit.size > 80) sb.append("…（只显示前 80 个）")
        ToolResult(true, sb.toString())
    }.getOrElse { ToolResult(false, "列应用失败：" + (it.message ?: "")) }

    /** 按显示名模糊找，返回它的启动 Intent（找不到回 null） */
    private fun findAppIntent(ctx: Context, name: String): Intent? = runCatching {
        val pm = ctx.packageManager
        val kw = name.lowercase(Locale.US)
        pm.getInstalledApplications(0)
            .sortedBy { runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault("") }
            .firstOrNull { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault("").lowercase(Locale.US)
                label == kw || (label.contains(kw) && kw.length >= 2)
            }
            ?.let { pm.getLaunchIntentForPackage(it.packageName) }
    }.getOrNull()

    private fun launch(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".gif", true) -> "image/gif"
        path.endsWith(".mp4", true) -> "video/mp4"
        path.endsWith(".mp3", true) -> "audio/mpeg"
        path.endsWith(".pdf", true) -> "application/pdf"
        path.endsWith(".apk", true) -> "application/vnd.android.package-archive"
        path.endsWith(".txt", true) || path.endsWith(".log", true) || path.endsWith(".md", true) -> "text/plain"
        path.endsWith(".json", true) -> "application/json"
        else -> "*/*"
    }

    /* ---------------- 截屏（要特权，照实说） ---------------- */

    /**
     * 截屏存成 PNG。
     *
     * ⚠️ 只有 root / Shizuku（shell 身份）能做到 —— 普通 app 权限的 `screencap`
     * 会被 SELinux 挡。这条**不假装能成功**：没特权就回一句"需要 root"，
     * 免得模型在那儿换写法硬撞（前一位在 shell 工具上反复撞过）。
     */
    suspend fun screenshot(ctx: Context, env: AgentEnv, path: String): ToolResult =
        withContext(Dispatchers.IO) {
            val level = Privilege.level(ctx)
            if (level == PrivLevel.Normal) {
                return@withContext ToolResult(
                    false,
                    "截屏需要 root 或 Shizuku（当前是普通权限，screencap 会被系统挡掉）。" +
                        "要么让用户开 Shizuku，要么这条路别试了 —— 换写法也没用。",
                )
            }
            val p = AgentRunner.guardPath(ctx, env, path.ifBlank { "screen.png" })
                ?: return@withContext ToolResult(false, "这个路径在沙箱外面：" + path)
            if (!p.endsWith(".png", true)) {
                return@withContext ToolResult(false, "截屏得存成 .png，给的是：" + p)
            }
            File(p).parentFile?.mkdirs()
            // 先落到能写的临时位置，再挪过去：screencap 以 shell 身份写，
            // 直接写 sdcard 在某些 ROM 上会因 u:object_r 标签失败
            val tmp = "/data/local/tmp/nakour_shot_${System.currentTimeMillis()}.png"
            val out = runCatching { Privilege.exec(ctx, "screencap -p " + tmp) }.getOrNull()
            if (out == null) {
                return@withContext ToolResult(false, "screencap 没跑成（提权失败？）。别再重试同样命令。")
            }
            runCatching { Privilege.exec(ctx, "cp $tmp " + p) }
            runCatching { Privilege.exec(ctx, "rm -f $tmp") }
            val f = File(p)
            if (!f.exists() || f.length() == 0L) {
                return@withContext ToolResult(
                    false,
                    "命令跑了但文件没落到 $p —— 多半是权限/SELinux 标签问题，说给用户听，别自己硬试。",
                )
            }
            ToolResult(true, "截屏存好了：$p（${f.length() / 1024} KB，${f.widthHint()}）")
        }

    private fun File.widthHint(): String = runCatching {
        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opt)
        "${opt.outWidth}×${opt.outHeight}"
    }.getOrDefault("?")

}
