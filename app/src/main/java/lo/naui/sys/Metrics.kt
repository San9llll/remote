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
    val rooted: Boolean = false,
)

/**
 * 系统指标读取。
 *
 * 读取策略（一条一条往下退，能拿到就用）：
 *   1. 公开 API —— 电量温度 / 电压 / 电流 / 内存 / 网络，不需要任何权限
 *   2. 直接读 /sys 与 /proc —— 大部分机器上这些节点是可读的
 *   3. `su -c cat <节点>` —— 只有前两条都拿不到、而且用户开着 root 开关时才用
 *
 * root 那条**只探测一次**（结果缓存着），不会每次刷新都弹授权框。
 */
object Metrics {

    private const val SU_TIMEOUT_MS = 1500L

    /* ---------------- root ---------------- */

    @Volatile private var rootChecked = false
    @Volatile private var rootOk = false

    fun rootAvailable(): Boolean {
        if (rootChecked) return rootOk
        synchronized(this) {
            if (rootChecked) return rootOk
            rootOk = probeRoot()
            rootChecked = true
            return rootOk
        }
    }

    fun resetRootCache() {
        rootChecked = false
        rootOk = false
    }

    private fun probeRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val done = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroy()
            false
        } else {
            val out = p.inputStream.bufferedReader().readText()
            out.contains("uid=0")
        }
    }.getOrDefault(false)

    /** 读一个节点：先直接读，读不到再问 root */
    private fun readNode(path: String, allowRoot: Boolean): String? {
        runCatching {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val t = f.readText().trim()
                if (t.isNotEmpty()) return t
            }
        }
        if (!allowRoot) return null
        if (!rootAvailable()) return null
        return runCatching {
            val p = ProcessBuilder("su", "-c", "cat $path").redirectErrorStream(true).start()
            val done = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroy()
                null
            } else {
                p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
            }
        }.getOrNull()
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
    private fun cpuTemp(allowRoot: Boolean): Float? {
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

        if (!allowRoot) return null
        listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
        ).forEach { p ->
            readNode(p, allowRoot)?.toFloatOrNull()?.let { return normalizeTemp(it) }
        }
        return null
    }

    /** 有的节点给的是毫摄氏度（45000），有的是摄氏度（45） */
    private fun normalizeTemp(raw: Float): Float =
        if (raw > 1000f) raw / 1000f else if (raw > 200f) raw / 10f else raw

    /* ---------------- 内存 ---------------- */

    private fun ram(allowRoot: Boolean): Float? {
        val text = readNode("/proc/meminfo", allowRoot) ?: return null
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
    private var lastCpuAt = 0L

    private fun cpuUsage(allowRoot: Boolean): Float? {
        val text = readNode("/proc/stat", allowRoot) ?: return null
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
        lastCpuAt = System.currentTimeMillis()

        if (prevTotal == 0L) return null
        val dt = total - prevTotal
        val di = idle - prevIdle
        if (dt <= 0L) return null
        return ((dt - di).toFloat() / dt * 100f).coerceIn(0f, 100f)
    }

    /* ---------------- GPU ---------------- */

    private fun gpu(allowRoot: Boolean): Float? {
        readNode("/sys/class/kgsl/kgsl-3d0/gpubusy", allowRoot)?.let { raw ->
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
            val raw = readNode(p, allowRoot) ?: return@forEach
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

    suspend fun sample(ctx: Context, allowRoot: Boolean): MetricsSnapshot = withContext(Dispatchers.IO) {
        val voltage = batteryVoltage(ctx)
        val current = batteryCurrent(ctx)
        MetricsSnapshot(
            batteryTempC = batteryTemp(ctx),
            cpuTempC = cpuTemp(allowRoot),
            ramPercent = ram(allowRoot),
            cpuUsagePercent = cpuUsage(allowRoot),
            gpuPercent = gpu(allowRoot),
            batteryPowerW = if (voltage != null && current != null) voltage * current else null,
            batteryVoltageV = voltage,
            batteryCurrentA = current,
            carrier = carrierName(ctx),
            netRateText = netRate()?.let { formatSpeed(it) } ?: "—",
            rooted = allowRoot && rootAvailable(),
        )
    }
}
