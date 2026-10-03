package lo.naui.xposed

import android.os.Build
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * hook 点的"自动适配"。
 *
 * ## 为什么要自动找
 *
 * 侧边栏是系统自己的 View，类名和字段名**每个系统版本都可能改**。
 * 靠人一个个逆出来（OShin 那么干的）太累，而且系统一升级就白干。
 * 所以改成：**让程序自己扫一遍，把像侧边栏的东西挑出来**。
 *
 * ## 结果写哪儿
 *
 * 写到 `/sdcard/Nakour/hook_probe.json`。
 *
 * 为什么不用 App 自己的 data 目录：这段代码跑在 **SystemUI 进程**里，
 * 那不是我们的进程，写不进我们的 `files/`。而 SystemUI 是系统应用、
 * 有存储权限，sdcard 是两边都能碰的地方。
 *
 * App 那边读这个文件就能显示进度条（用户要的"真实的进度"）。
 *
 * ## 什么时候跑
 *
 * App 每次启动会把当前的"系统指纹"（版本 + 安全补丁 + 侧边栏相关包版本）
 * 写进一个标记文件。这里对一下：
 *   - 没见过这个指纹 → 跑一次
 *   - 见过 → 跳过
 *
 * 所以"初次进入软件"和"系统更新之后"这两种情况都会自动适配。
 */
object HookProbe {

    private const val TAG = "Nakour"

    /** 两边约定的目录 */
    private val DIR = File("/sdcard/Nakour")

    /** 进度 + 结果都写这儿 */
    private val OUT = File(DIR, "hook_probe.json")

    /** App 写的"当前系统指纹"，跟它比就知道要不要重扫 */
    private val STAMP = File(DIR, "sys_stamp.txt")

    /** 扫完之后把选中的 hook 点记这儿 */
    private val PICKED = File(DIR, "hook_point.txt")

    /** 名字里带这些的，先当成候选 */
    private val KEYS = listOf(
        "sidebar", "SideBar", "Sidebar", "side_bar",
        "panel", "Panel",
        "edge", "Edge",
        "smart", "Smart",
        "assist", "Assist",
        "dock", "Dock",
        "floating", "Floating",
    )

    /** 明显不是侧边栏的，排掉（不然一堆噪音） */
    private val SKIP = listOf(
        "com.android.systemui.media", "com.android.systemui.volume",
        "com.android.systemui.statusbar.notification",
        "com.android.systemui.keyguard",
        "com.android.systemui.screenshot",
        "com.android.systemui.recents",
        "com.android.systemui.qs",
    )

    /** 报个活 —— App 靠这个文件判断模块到底有没有被框架加载 */
    fun sayAlive() {
        runCatching {
            if (!DIR.exists()) DIR.mkdirs()
            File(DIR, "xposed_alive.txt").writeText(System.currentTimeMillis().toString())
        }
    }

    /** 现阶段的系统指纹 */
    fun stamp(): String = runCatching {
        listOf(
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT.toString(),
            Build.VERSION.SECURITY_PATCH ?: "",
            Build.DISPLAY ?: "",
            Build.MANUFACTURER,
            Build.DEVICE,
        ).joinToString("|")
    }.getOrDefault("unknown")

    /* ================= 把类名捞出来 ================= */

    /**
     * 从 ClassLoader 的 dex 里把**所有类名**枚举出来。
     *
     * ClassLoader 没给"列个清单"的 API，只能往下扒：
     *   ClassLoader → pathList → dexElements[] → dexFile → entries()
     *
     * `DexFile.entries()` 从 Android 8 起就不是公开 API 了，
     * 不过反射还能调 —— Xposed 环境下这本来就是家常便饭。
     *
     * 慢是慢（SystemUI 好几千个类），但只在适配时跑一次。
     */
    fun listClassNames(cl: ClassLoader?): List<String> {
        if (cl == null) return emptyList()
        val out = LinkedHashSet<String>()

        runCatching {
            val pathList = XposedHelpers.getObjectField(cl, "pathList") ?: return emptyList<String>()
            val elements = XposedHelpers.getObjectField(pathList, "dexElements") as? Array<*> ?: return emptyList<String>()

            elements.forEach { el ->
                if (el == null) return@forEach
                val dexFile = runCatching { XposedHelpers.getObjectField(el, "dexFile") }.getOrNull() ?: return@forEach

                // 有的版本 entries() 直接给 Enumeration<String>
                val byMethod = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    XposedHelpers.callMethod(dexFile, "entries") as? java.util.Enumeration<*>
                }.getOrNull()

                if (byMethod != null) {
                    while (byMethod.hasMoreElements()) {
                        (byMethod.nextElement() as? String)?.let { out += it }
                    }
                } else {
                    // 另一些版本要读 mInternalDexFile 再取
                    runCatching {
                        val internal = XposedHelpers.getObjectField(dexFile, "mInternalDexFile")
                        val en = XposedHelpers.callMethod(internal, "entries") as? java.util.Enumeration<*>
                        en?.let {
                            while (it.hasMoreElements()) {
                                (it.nextElement() as? String)?.let { n -> out += n }
                            }
                        }
                    }
                }
            }
        }.onFailure { log("枚举类名失败：${it.message}") }

        log("dex 里一共 ${out.size} 个类")
        return out.toList()
    }

    /* ================= 写进度 ================= */

    private fun report(progress: Float, stage: String, found: List<String>, done: Boolean) {
        runCatching {
            if (!DIR.exists()) DIR.mkdirs()
            val arr = found.joinToString(",\n    ") { "\"" + it.replace("\"", "\\\"") + "\"" }
            OUT.writeText(
                """
                {
                  "progress": $progress,
                  "stage": ${q(stage)},
                  "done": $done,
                  "stamp": ${q(stamp())},
                  "at": ${System.currentTimeMillis()},
                  "candidates": [
                    $arr
                  ]
                }
                """.trimIndent()
            )
        }
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun log(msg: String) = runCatching { XposedBridge.log("[$TAG] $msg") }

    /* ================= 要不要扫 ================= */

    /** 已经适配过了吗（系统指纹没变的话就不用再扫） */
    fun alreadyAdapted(): Boolean {
        val now = stamp()
        val last = runCatching { STAMP.readText().trim() }.getOrNull() ?: return false
        val picked = PICKED.exists() && PICKED.readText().isNotBlank()
        return last == now && picked
    }

    /**
     * 跑一遍适配。
     *
     * [classNames] 是当前进程里能看到的类名列表 —— 由调用方从
     * Application 的 ClassLoader 那边拿（说白了就是让它把已知的类报过来）。
     * 拿不到也照样往下走，只是候选会少一些。
     */
    fun adapt(classNames: List<String>) {
        if (alreadyAdapted()) {
            log("系统指纹没变，跳过适配")
            report(1f, "已适配（系统没变）", readPicked(), true)
            return
        }

        log("开始适配 hook 点，当前系统指纹：${stamp()}")
        report(0.05f, "准备中", emptyList(), false)

        val candidates = mutableListOf<String>()

        // 第一步：名字里带关键词的类
        report(0.15f, "正在筛类名", emptyList(), false)
        classNames.forEachIndexed { i, name ->
            if (KEYS.any { name.contains(it) } && SKIP.none { name.startsWith(it) }) {
                candidates += name
            }
            // 每筛 200 个报一次进度（15% ~ 55% 这一段）
            if (i % 200 == 0) {
                report(0.15f + 0.40f * i / classNames.size.coerceAtLeast(1), "正在筛类名（$i/${classNames.size}）", candidates, false)
            }
        }
        report(0.55f, "筛出 ${candidates.size} 个候选", candidates, false)

        // 第二步：挑出最像"侧边栏本身"的那几个
        // 判据：名字里同时有 sidebar/panel 且不是 xxxItem / xxxAdapter 这种零件
        report(0.65f, "正在挑主类", candidates, false)
        val mains = candidates.filter { n ->
            val lower = n.lowercase()
            (lower.contains("sidebar") || lower.contains("side_bar") ||
                lower.contains("panel") || lower.contains("dock")) &&
                !lower.endsWith("item") && !lower.endsWith("adapter") &&
                !lower.contains("$")
        }
        report(0.80f, "挑出 ${mains.size} 个主类", mains, false)

        // 第三步：落盘
        report(0.92f, "正在保存结果", mains, false)
        runCatching {
            if (!DIR.exists()) DIR.mkdirs()
            PICKED.writeText(mains.joinToString("\n"))
            STAMP.writeText(stamp())
        }

        report(1f, "适配完成，挑出 ${mains.size} 个", mains, true)
        log("适配完成：${mains.joinToString(", ")}")
    }

    private fun readPicked(): List<String> =
        runCatching { PICKED.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
