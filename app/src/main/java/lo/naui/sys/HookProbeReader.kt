package lo.naui.sys

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * LSPosed 到底生效了没。
 *
 * 判断方式很土：Xposed 那边每次挂进 SystemUI 都会写一个 `xposed_alive.txt`，
 * 这边看它在不在。在 = 模块确实被框架加载过。
 *
 * 没有更好的办法 —— App 和框架之间没有官方通道，只能靠这种"留痕"。
 */
object XposedActive {

    private val ALIVE = File("/sdcard/Nakour/xposed_alive.txt")

    fun isActive(ctx: Context): Boolean = runCatching { ALIVE.exists() }.getOrDefault(false)

    /** 那个文件是 `时间戳|API版本|框架名`，切一下 */
    private fun parts(): List<String> = runCatching {
        if (!ALIVE.exists()) emptyList()
        else ALIVE.readText().trim().split("|")
    }.getOrDefault(emptyList())

    /** 它上次报活是什么时候 */
    fun lastSeen(): String = runCatching {
        val t = parts().getOrNull(0)?.toLongOrNull() ?: return@runCatching ""
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(t))
    }.getOrDefault("")

    /** Xposed API 版本（LSPosed 一般给 82 / 93） */
    fun apiVersion(): Int = parts().getOrNull(1)?.toIntOrNull() ?: 0

    /** 框架名（LSPosed / EdXposed / Xposed） */
    fun framework(): String = parts().getOrNull(2)?.takeIf { it.isNotBlank() } ?: ""

    /**
     * LSPosed 管理器的版本。
     *
     * 框架本身没提供"查我版本号"的 API，但管理器（`org.lsposed.manager`）
     * 跟框架是一起发布的，版本号对得上 —— 所以从那儿读。
     * 没装管理器（比如只刷了框架）就拿不到，返回空串。
     */
    fun managerVersion(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo("org.lsposed.manager", 0)
        info.versionName ?: ""
    }.getOrDefault("")

    /**
     * 主页那栏要显示的那句话。
     *
     * 例：`LSPosed 1.9.2 · API 93`
     */
    fun summary(ctx: Context): String {
        if (!isActive(ctx)) return "未生效"
        val fw = framework().ifBlank { "Xposed" }
        val api = apiVersion()
        val mgr = managerVersion(ctx)
        return buildString {
            append(fw)
            if (mgr.isNotBlank()) append(' ').append(mgr)
            if (api > 0) {
                if (isNotEmpty()) append(" · ")
                append("API ").append(api)
            }
        }
    }
}

/** 适配进度的一个快照 */
data class ProbeState(
    /** 有结果文件吗 */
    val exists: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val done: Boolean = false,
    /** 挑出来的候选类 */
    val candidates: List<String> = emptyList(),
    /** Xposed 侧记下来的系统指纹 */
    val stamp: String = "",
    val at: Long = 0L,
)

/**
 * 读 hook 点的自动适配进度。
 *
 * 两边靠一个文件说话：Xposed 那边（跑在 SystemUI 进程里）扫的时候
 * 把进度写 `/sdcard/Nakour/hook_probe.json`，这边读出来画进度条。
 *
 * 为什么不走别的：跨进程通信要 Binder / Provider，
 * 而这段代码的调用方是**系统进程**，写不进我们的 data 目录 ——
 * sdcard 是两边都能碰的、最省事的地方。
 */
object HookProbeReader {

    /** 跟 Xposed 那边约定的目录 */
    fun dir(): File = File("/sdcard/Nakour")

    private fun out(): File = File(dir(), "hook_probe.json")

    private fun stampFile(): File = File(dir(), "sys_stamp.txt")

    private fun pickedFile(): File = File(dir(), "hook_point.txt")

    /** 当前系统的指纹（Xposed 那边用同样的算法，用来判断要不要重扫） */
    fun sysStamp(): String = runCatching {
        listOf(
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT.toString(),
            Build.VERSION.SECURITY_PATCH ?: "",
            Build.DISPLAY ?: "",
            Build.MANUFACTURER,
            Build.DEVICE,
        ).joinToString("|")
    }.getOrDefault("unknown")

    /** 读一次进度 */
    suspend fun read(): ProbeState = withContext(Dispatchers.IO) {
        runCatching {
            val f = out()
            if (!f.exists()) return@runCatching ProbeState()

            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("candidates")
            val list = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { list += it }
                }
            }
            ProbeState(
                exists = true,
                progress = o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                stage = o.optString("stage", ""),
                done = o.optBoolean("done", false),
                candidates = list,
                stamp = o.optString("stamp", ""),
                at = o.optLong("at", 0L),
            )
        }.getOrDefault(ProbeState())
    }

    /**
     * 这套东西到底有没有在跑。
     *
     * 判断依据：结果文件里的系统指纹，跟当前系统对不对得上。
     * 对不上就说明**系统更新过、还没重新适配**。
     */
    suspend fun status(ctx: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            val st = read()
            when {
                !XposedActive.isActive(ctx) ->
                    "没检测到 LSPosed 生效 —— 先在 LSPosed 里启用本模块、作用域勾「系统界面」，然后重启系统界面"
                !st.exists ->
                    "还没扫过。把模块启上、重启一次系统界面，这边会自动开始适配"
                st.stamp != sysStamp() ->
                    "系统更新过了（指纹变了），等系统界面重启后会自动重新适配"
                st.done -> "已适配（挑出 " + st.candidates.size + " 个候选）"
                else -> "正在适配…"
            }
        }.getOrDefault("")
    }

    /** 读 Xposed 那边最后挑出来的 hook 点 */
    suspend fun picked(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val f = pickedFile()
            if (!f.exists()) emptyList()
            else f.readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * 让 Xposed 那边下次重扫。
     *
     * 做法很土但有效：把记录指纹的那个文件删掉 ——
     * 那边一看"没见过这个指纹"就会重新跑一遍。
     */
    suspend fun requestRescan(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            stampFile().delete()
            pickedFile().delete()
            true
        }.getOrDefault(false)
    }

    /** 这个文件写得进去吗（没权限的话提示用户） */
    fun canWrite(): Boolean = runCatching {
        val d = dir()
        if (!d.exists()) d.mkdirs()
        val t = File(d, ".probe_test")
        t.writeText("ok")
        t.delete()
        true
    }.getOrDefault(false)

    var lastState by mutableStateOf(ProbeState())
        private set

    fun cache(s: ProbeState) {
        lastState = s
    }
}
