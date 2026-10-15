package lo.naui.sys

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Shizuku 的包名（装了这个才能走 adb 免 root 的那条路） */
const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Shizuku 的状态：装没装 / 服务跑没跑 / 本应用授权没授权 */
data class ShizukuInfo(
    val installed: Boolean = false,
    val appVersion: String = "",
    val running: Boolean = false,
    val serverVersion: Int = 0,
    val granted: Boolean = false,
) {
    /** 右上角那个标记 */
    val tag: String
        get() = when {
            !installed -> "未安装"
            running && granted -> "已连接"
            running -> "待授权"
            else -> "未启动"
        }
}

/**
 * Shizuku 识别。
 *
 * 跟 root 那条路是并行的两种权限来源：
 *   root  —— su 直接给 uid 0
 *   adb  —— Shizuku 用 adb 起的服务，免 root 也能拿到一部分系统能力
 *
 * 用官方那套（dev.rikka.shizuku:api + provider）：
 *   - 装没装走 PackageManager（Android 11+ 要在 manifest 里加 <queries>，否则看不见）
 *   - 服务在不在走 Shizuku.pingBinder()，没装 Shizuku 时它安全地返回 false
 */
object ShizukuState {

    @Volatile private var cached: ShizukuInfo? = null

    suspend fun info(ctx: Context, force: Boolean = false): ShizukuInfo =
        withContext(Dispatchers.IO) {
            if (!force) cached?.let { return@withContext it }
            val i = detect(ctx)
            cached = i
            i
        }

    fun cachedOrNull(): ShizukuInfo? = cached

    private fun detect(ctx: Context): ShizukuInfo {
        var installed = false
        var appVersion = ""
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            installed = true
            appVersion = pi.versionName.orEmpty()
        }

        // 没装 Shizuku 时这里只会返回 false，不会抛
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        val serverVersion = if (running) {
            runCatching { Shizuku.getVersion() }.getOrDefault(0)
        } else {
            0
        }

        val granted = if (running) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }

        return ShizukuInfo(
            installed = installed,
            appVersion = appVersion,
            running = running,
            serverVersion = serverVersion,
            granted = granted,
        )
    }
}
