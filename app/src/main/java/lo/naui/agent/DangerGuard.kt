package lo.naui.agent

import org.json.JSONObject

/**
 * 危险动作的闸门。
 *
 * AI 手里有 root，一条 `dd of=/dev/block/by-name/boot` 就能把手机写死。
 * 所以凡是碰到下面这八类，**执行前必须让人看一眼** —— 而且要把
 * **具体命令**摆出来，不能只说个"危险"就完事。
 *
 * 分类和后果都是照着实战踩出来的：
 *   1 删系统/数据      照片、聊天记录没了
 *   2 写块设备/分区    最可能硬变砖
 *   3 格式化/分区表    基本不可恢复
 *   4 权限/SELinux     开机卡住；关 SELinux 等于门户大开
 *   5 进程/内核        立刻重启、死机、panic
 *   6 网络/防火墙      断网失联（远程调试就再也连不上）
 *   7 启动配置/系统文件  卡开机、bootloop
 *   8 刷机/fastboot    变砖、清数据、锁不上
 *
 * 关键：**这不是禁止，只是先问一句**。用户明确要能真操作底层，
 * 那就别拦死，但也不能闭眼放行。
 */
object DangerGuard {

    /** 一类危险动作 */
    data class Category(
        val id: String,
        val label: String,
        val note: String,
        val consequence: String,
        val regexes: List<String>,
    )

    /** 命中的结果 */
    data class Hit(
        val category: Category,
        /** 具体匹配到的片段，弹窗里要展示的就是它 */
        val matched: String,
    )

    val CATEGORIES: List<Category> = listOf(
        Category(
            id = "wipe_data",
            label = "删系统 / 数据",
            note = "删了基本找不回来",
            consequence = "系统无法启动、所有照片和聊天记录丢失、" +
                "应用密钥与锁屏密码异常，严重时直接变砖。",
            regexes = listOf(
                """rm\s+-[a-zA-Z]*[rR][a-zA-Z]*f[a-zA-Z]*\s+(/\s*$|/\*|/\s)""",
                """rm\s+-[a-zA-Z]*f[a-zA-Z]*[rR][a-zA-Z]*\s+(/\s*$|/\*|/\s)""",
                """rm\s+-[a-zA-Z]*[rR][a-zA-Z]*f[a-zA-Z]*\s+/(system|vendor|product|data|cache|boot|sdcard|storage/emulated|mnt)\b""",
                """rm\s+-[a-zA-Z]*f[a-zA-Z]*[rR][a-zA-Z]*\s+/(system|vendor|product|data|cache|boot|sdcard|storage/emulated|mnt)\b""",
                """rm\s+.*\s/data/(system|misc/keystore)\b""",
            ),
        ),
        Category(
            id = "block_write",
            label = "写块设备 / 分区",
            note = "最可能硬变砖",
            consequence = "boot / recovery / system / vendor / super / userdata / " +
                "modem / persist / efs / nvram / frp / bootloader / abl / xbl 这些分区只要写错一个，" +
                "就可能开不了机、IMEI 与基带丢失、指纹传感器失效，而且不一定救得回来。",
            regexes = listOf(
                """dd\s+[^\n]*of=/dev/block""",
                """dd\s+[^\n]*of=/dev/sd[a-z]""",
                """cat\s+/dev/zero\s*>\s*/dev/block""",
                """>\s*/dev/block/(by-name|sd[a-z]|mmcblk)""",
                """echo\s+[^\n]*>\s*/dev/block""",
                """dd\s+[^\n]*of=/dev/mapper""",
            ),
        ),
        Category(
            id = "format",
            label = "格式化 / 分区表",
            note = "数据基本不可恢复",
            consequence = "分区被清空或者分区表被改写，里面的东西很难再恢复。",
            regexes = listOf(
                """\bmkfs(\.[a-z0-9]+)?\b""",
                """\bwipefs\b""",
                """\bfdisk\b""",
                """\bparted\b""",
                """\bsgdisk\b""",
                """\bmke2fs\b""",
            ),
        ),
        Category(
            id = "perm_selinux",
            label = "权限 / SELinux",
            note = "会让系统起不来或门户大开",
            consequence = "权限被改乱，系统服务启不来，出现 bootloop；" +
                "关掉 SELinux 则让恶意应用更容易提权、偷数据。",
            regexes = listOf(
                """chmod\s+-R\s+777\s+/\s*$""",
                """chmod\s+-R\s+[0-7]{3,4}\s+/(system|data|vendor)\b""",
                """chown\s+-R\s+[^\s]+\s+/\s*$""",
                """chmod\s+000\s+/system/bin""",
                """\bsetenforce\s+0""",
                """>\s*/sys/fs/selinux/enforce""",
                """chcon\s+""",
            ),
        ),
        Category(
            id = "process_kernel",
            label = "进程 / 内核",
            note = "立刻崩溃或重启",
            consequence = "系统立即崩溃、重启、死机，严重的话内核直接 panic。",
            regexes = listOf(
                """kill\s+-9\s+-1""",
                """\bpkill\s+-9\b""",
                """\bkillall\s+-9\b""",
                """kill\s+-9\s+1\b""",
                """echo\s+[bc]\s*>\s*/proc/sysrq-trigger""",
                """sysrq-trigger""",
                """:\(\)\s*\{.*\}""",          // fork 炸弹
                """\(\)\s*\{\s*:\|:&\s*\}""",
            ),
        ),
        Category(
            id = "network",
            label = "网络 / 防火墙",
            note = "容易把自己搞失联",
            consequence = "断网、失联。如果是靠远程 adb 或无线调试连着的，可能再也连不上。",
            regexes = listOf(
                """iptables\s+-F""",
                """iptables\s+-P\s+(INPUT|OUTPUT|FORWARD)\s+DROP""",
                """ip\s+link\s+set\s+\w+\s+down""",
                """ip\s+route\s+del\s+default""",
                """\bnft\s+(flush|delete)\b""",
                """setprop\s+net\.""",
            ),
        ),
        Category(
            id = "boot_config",
            label = "启动配置 / 系统文件",
            note = "改坏就卡开机",
            consequence = "卡开机、bootloop、进不去系统。" +
                "要改系统优先用 Magisk 的 systemless 模块，别直接动 /system。",
            regexes = listOf(
                """>\s*/system/build\.prop""",
                """sed\s+[^\n]*\s/system/build\.prop""",
                """>\s*/vendor/etc/fstab""",
                """\s/system/etc/init\.rc""",
                """\s/init\.rc""",
                """rm\s+[^\n]*/system/bin/(sh|app_process|linker)""",
                """\bmount\s+-o\s+rw[^\n]*\s/system""",
                """\bmount\s+-o\s+remount[^\n]*\s/system""",
            ),
        ),
        Category(
            id = "flash",
            label = "刷机 / fastboot",
            note = "变砖、清数据",
            consequence = "擦错分区或者刷错镜像就是变砖；" +
                "oem lock / flashing lock 之后可能再也解不开。",
            regexes = listOf(
                """fastboot\s+erase""",
                """fastboot\s+flash""",
                """fastboot\s+oem\s+lock""",
                """fastboot\s+flashing\s+lock""",
                """fastboot\s+-w""",
            ),
        ),
    )

    /* ---------------- 检测 ---------------- */

    /** 这条命令碰到哪一类了（没碰到返回 null） */
    fun check(command: String): Hit? {
        if (command.isBlank()) return null
        val c = command.replace(Regex("\\s+"), " ").trim()

        CATEGORIES.forEach { cat ->
            cat.regexes.forEach { pattern ->
                val m = runCatching { Regex(pattern, RegexOption.IGNORE_CASE).find(c) }.getOrNull()
                if (m != null) return Hit(cat, m.value.trim())
            }
        }
        return null
    }

    /** 写文件时碰到这些目录也要问 */
    private val SENSITIVE_PATHS = listOf(
        "/system", "/vendor", "/product", "/boot", "/data/adb",
        "/data/system", "/data/misc", "/dev/block", "/proc/sys",
        "/sys/fs/selinux", "/init.rc",
    )

    fun checkWrite(path: String): Hit? {
        val p = path.lowercase()
        val hit = SENSITIVE_PATHS.firstOrNull { p.startsWith(it) } ?: return null
        val cat = CATEGORIES.first { it.id == "boot_config" }
        return Hit(cat, "写文件：" + path + "（落在 " + hit + " 里）")
    }

    /** 不管什么工具，统一过一道 */
    fun risk(tool: String, args: JSONObject): Hit? = when (tool) {
        AgentTools.SHELL -> check(args.optString("command", ""))
        AgentTools.WRITE -> checkWrite(args.optString("path", ""))
        else -> null
    }

    /** 碰上危险动作怎么办 */
    enum class Policy(val id: String, val label: String, val summary: String) {
        Ask("ask", "每次都问", "执行前弹一下，你点同意才跑 —— 默认"),
        Allow("allow", "一律放行", "不再问，AI 说跑就跑（想清楚再选）"),
        Deny("deny", "一律拒绝", "危险动作直接挡掉，AI 会收到一条拒绝"),
    }
}
