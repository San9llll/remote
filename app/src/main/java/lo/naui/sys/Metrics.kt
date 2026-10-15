package lo.naui.sys

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.telephony.TelephonyManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次采样拿到的所有数字（拿不到的留 null，界面上显示成 —） */
data class MetricsSnapshot(
    val batteryTempC: Float? = null,
    val cpuTempC: Float? = null,
    val ramPercent: Float? = null,
    val cpuUsagePercent: Float? = null,
    val gpuPercent: Float? = null,
    val batteryPowerW: Float? = null,
    val batteryVoltageV: Float? = null,
    val batteryCurrentA: Float? = null,
    val carrier: String = "",
    val netRateText: String = "—",
)

/** 拿到手的 root 是什么、什么版本 */
data class RootInfo(
    val granted: Boolean = false,
    val name: String = "未获取",
    val version: String = "",
    val detail: String = "su 不可用",
)

/**
 * 系统指标 + root 信息。
 *
 * 读取策略（一条一条往下退，能拿到就用）：
 *   1. 公开 API —— 电量温度 / 电压 / 电流 / 内存 / 网络，不需要任何权限
 *   2. 直接读 /sys 与 /proc —— 大部分机器上这些节点是可读的
 *   3. `su -c cat <节点>` —— 前两条都拿不到时才用
 *
 * root 是**全局假定可用**的（设置里不给开关）：只在真的需要读受限节点时才去问一次，
 * 结果一直缓存着，不会每两秒弹一次授权框。
 */
object Metrics {

    // 第一次调 su 会弹授权框，用户点"允许"得花几秒 —— 给够时间。
    // 之前 1.5 秒就超时，超时被当成"没有 root"**永久缓存**，
    // 于是用户明明授权了，App 还是当自己没有 root（文件管理连 /data 都进不去就是这个）。
    private const val SU_TIMEOUT_MS = 6000L

    // 失败的结果只记这么久，过一会儿再问一次
    private const val ROOT_RETRY_MS = 15_000L

    /* ---------------- root ---------------- */

    @Volatile private var rootChecked = false
    @Volatile private var rootOk = false
    @Volatile private var rootCheckedAt = 0L

    /**
     * 有没有 root。
     *
     * 成功过一次就永远算成功；**失败只记 15 秒**，过了再问一次 ——
     * 这样用户在授权框上点完"允许"，最多十几秒就能用上，
     * 不用杀进程重开。
     */
    fun rootAvailable(): Boolean {
        if (rootOk) return true
        if (rootChecked && System.currentTimeMillis() - rootCheckedAt < ROOT_RETRY_MS) return false
        synchronized(this) {
            if (rootOk) return true
            if (rootChecked && System.currentTimeMillis() - rootCheckedAt < ROOT_RETRY_MS) return false
            rootOk = probeRoot()
            rootChecked = true
            rootCheckedAt = System.currentTimeMillis()
            return rootOk
        }
    }

    fun resetRootCache() {
        rootChecked = false
        rootOk = false
        cachedRoot = null
    }

    private fun probeRoot(): Boolean = runCatching {
        val out = suExec("id") ?: return@runCatching false
        out.contains("uid=0")
    }.getOrDefault(false)

    /** 跑一条 su 命令，超时就杀掉 */
    private fun suExec(script: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val done = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroy()
            null
        } else {
            p.inputStream.bufferedReader().readText().trim()
        }
    }.getOrNull()

    /* ---------------- root 是哪一家 ---------------- */

    private val PROBE = listOf(
        "id -u",
        "[ -d /data/adb/ap ] && echo HAS_AP",
        "[ -x /data/adb/apd ] && echo HAS_AP",
        "[ -d /data/adb/ksu ] && echo HAS_KSU",
        "[ -d /data/adb/magisk ] && echo HAS_MAGISK",
        "magisk -v 2>/dev/null | head -n 1 | sed 's/^/V_MAGISK=/'",
        "ksud -V 2>/dev/null | head -n 1 | sed 's/^/V_KSU=/'",
        "apd -V 2>/dev/null | head -n 1 | sed 's/^/V_AP=/'",
        "echo DONE",
    ).joinToString("; ")

    @Volatile private var cachedRoot: RootInfo? = null

    /** 拿一次 root 的实现和版本，之后一直用缓存（会起 process，别在主线程直接调） */
    suspend fun rootInfo(): RootInfo = withContext(Dispatchers.IO) {
        cachedRoot ?: detectRoot().also { cachedRoot = it }
    }

    private fun detectRoot(): RootInfo {
        if (!rootAvailable()) {
            return RootInfo(granted = false, name = "未获取", version = "", detail = "su 不可用或被拒绝")
        }
        val out = suExec(PROBE)
        if (out.isNullOrBlank()) {
            return RootInfo(granted = true, name = "su", version = "", detail = "su 可用，但认不出是哪一家")
        }
        val lines = out.lineSequence().map { it.trim() }.toList()
        val uidOk = lines.firstOrNull()?.trim() == "0"
        val hasAp = lines.contains("HAS_AP")
        val hasKsu = lines.contains("HAS_KSU")
        val hasMagisk = lines.contains("HAS_MAGISK")
        val vAp = lines.firstOrNull { it.startsWith("V_AP=") }?.removePrefix("V_AP=")
        val vKsu = lines.firstOrNull { it.startsWith("V_KSU=") }?.removePrefix("V_KSU=")
        val vMagisk = lines.firstOrNull { it.startsWith("V_MAGISK=") }?.removePrefix("V_MAGISK=")

        val name = when {
            vAp != null || hasAp -> "APatch"
            vKsu != null || hasKsu -> "KernelSU"
            vMagisk != null || hasMagisk -> "Magisk"
            else -> "su"
        }
        val version = cleanVersion(vAp ?: vKsu ?: vMagisk)

        return RootInfo(
            granted = uidOk,
            name = if (uidOk) name else "su（未授权）",
            version = version,
            detail = if (uidOk) "UID 0 · 已授权" else "su 在，但没拿到 uid 0",
        )
    }

    private fun cleanVersion(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.lineSequence().firstOrNull().orEmpty().trim()
        listOf("APatch version:", "KernelSU version:", "Magisk version:", "version:").forEach { p ->
            if (s.startsWith(p, ignoreCase = true)) s = s.substring(p.length).trim()
        }
        return s
    }

    /* ---------------- 读节点 ---------------- */

    /**
     * 读一个节点：先直接读，**只有"节点存在但读不出来"才去问 root**。
     * 节点压根不存在的（很多机型没有 GPU 那几个）直接返回 null ——
     * 不然每 2 秒就要为它白起一次 su 进程。
     */
    private fun readNode(path: String): String? {
        val f = File(path)
        val exists = runCatching { f.exists() }.getOrDefault(false)
        if (!exists) return null

        runCatching {
            if (f.canRead()) {
                val t = f.readText().trim()
                if (t.isNotEmpty()) return t
            }
        }
        return suExec("cat $path")?.trim()?.ifEmpty { null }
    }

    /* ---------------- 电池 ---------------- */

    private fun batteryIntent(ctx: Context): Intent? =
        runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

    private fun batteryTemp(ctx: Context): Float? {
        val v = batteryIntent(ctx)?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        return if (v == Int.MIN_VALUE) null else v / 10f
    }

    private fun batteryVoltage(ctx: Context): Float? {
        val v = batteryIntent(ctx)?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        return if (v == Int.MIN_VALUE || v <= 0) null else v / 1000f
    }

    /** 电流，安培。正 = 充电，负 = 放电 */
    private fun batteryCurrent(ctx: Context): Float? {
        val bm = runCatching {
            ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull() ?: return null
        val ua = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE)
        if (ua == Int.MIN_VALUE || ua == 0) return null
        return ua / 1_000_000f
    }

    /* ---------------- 温度 ---------------- */

    /** 热区里挑一个像 CPU/SOC 的 */
    private fun cpuTemp(): Float? {
        val picks = listOf("cpu", "soc", "tsens", "ap", "big", "little", "cluster")
        runCatching {
            val dir = File("/sys/class/thermal")
            val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") }
                ?: return@runCatching
            val matched = zones.firstOrNull { z ->
                val type = runCatching { File(z, "type").readText().trim().lowercase() }
                    .getOrDefault("")
                picks.any { type.contains(it) }
            }
            val zone = matched ?: zones.firstOrNull() ?: return@runCatching
            val raw = runCatching { File(zone, "temp").readText().trim() }.getOrNull()
                ?: return@runCatching
            val value = raw.toFloatOrNull() ?: return@runCatching
            return normalizeTemp(value)
        }

        listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
        ).forEach { p ->
            readNode(p)?.toFloatOrNull()?.let { return normalizeTemp(it) }
        }
        return null
    }

    /** 有的节点给的是毫摄氏度（45000），有的是摄氏度（45） */
    private fun normalizeTemp(raw: Float): Float =
        if (raw > 1000f) raw / 1000f else if (raw > 200f) raw / 10f else raw

    /* ---------------- 内存 ---------------- */

    private fun ram(): Float? {
        val text = readNode("/proc/meminfo") ?: return null
        var total = 0L
        var available = -1L
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                line.startsWith("MemAvailable:") ->
                    available = line.filter { it.isDigit() }.toLongOrNull() ?: -1L
            }
        }
        if (total <= 0L || available < 0L) return null
        return ((total - available).toFloat() / total * 100f).coerceIn(0f, 100f)
    }

    /* ---------------- CPU ---------------- */

    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L

    private fun cpuUsage(): Float? {
        val text = readNode("/proc/stat") ?: return null
        val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val parts = line.trim().split(" ").filter { it.isNotBlank() }.drop(1)
            .mapNotNull { it.toLongOrNull() }
        if (parts.size < 5) return null

        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
        val total = parts.sum()
        val prevTotal = lastCpuTotal
        val prevIdle = lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idle

        if (prevTotal == 0L) return null
        val dt = total - prevTotal
        val di = idle - prevIdle
        if (dt <= 0L) return null
        return ((dt - di).toFloat() / dt * 100f).coerceIn(0f, 100f)
    }

    /* ---------------- GPU ---------------- */

    private fun gpu(): Float? {
        readNode("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let { raw ->
            val nums = raw.trim().split(" ").filter { it.isNotBlank() }
                .mapNotNull { it.toFloatOrNull() }
            if (nums.size >= 2 && nums[1] > 0f) {
                return (nums[0] / nums[1] * 100f).coerceIn(0f, 100f)
            }
        }
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_busy_percent",
            "/sys/module/ged/parameters/gpu_loading",
            "/sys/class/devfreq/gpufreq/load",
        ).forEach { p ->
            val raw = readNode(p) ?: return@forEach
            val v = raw.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: return@forEach
            if (v > 0f) return v.coerceIn(0f, 100f)
        }
        return null
    }

    /* ---------------- 网络 ---------------- */

    private var lastNetBytes = -1L
    private var lastNetAt = 0L

    private fun carrierName(ctx: Context): String = runCatching {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.trim().orEmpty()
    }.getOrDefault("")

    private fun netRate(): Long? {
        val now = System.currentTimeMillis()
        val total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        if (total <= 0L) return null
        val prev = lastNetBytes
        val prevAt = lastNetAt
        lastNetBytes = total
        lastNetAt = now
        if (prev < 0L || now <= prevAt) return null
        val bytes = total - prev
        if (bytes < 0L) return null
        return bytes * 1000L / (now - prevAt)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        val b = bytesPerSec.toFloat()
        return when {
            b < 1024f -> b.toInt().toString() + "B/s"
            b < 1024f * 1024f -> one(b / 1024f) + "K/s"
            else -> one(b / 1024f / 1024f) + "M/s"
        }
    }

    private fun one(v: Float): String = ((v * 10f).toInt() / 10f).toString()

    /* ---------------- 采样 ---------------- */

    suspend fun sample(ctx: Context): MetricsSnapshot = withContext(Dispatchers.IO) {
        val voltage = batteryVoltage(ctx)
        val current = batteryCurrent(ctx)
        MetricsSnapshot(
            batteryTempC = batteryTemp(ctx),
            cpuTempC = cpuTemp(),
            ramPercent = ram(),
            cpuUsagePercent = cpuUsage(),
            gpuPercent = gpu(),
            batteryPowerW = if (voltage != null && current != null) voltage * current else null,
            batteryVoltageV = voltage,
            batteryCurrentA = current,
            carrier = carrierName(ctx),
            netRateText = netRate()?.let { formatSpeed(it) } ?: "—",
        )
    }
}
