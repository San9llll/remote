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
                // ⚠️ 这里**故意放宽**：只要在删东西就问，不强求带 -f/-r。
                // 之前只认 `rm -rf` 这类，结果 AI 用普通 `rm` 删文件时完全不拦 ——
                // 用户报"拦截没生效"就是这么来的。
                """\brm\s+""",
                """\brmdir\s+""",
                """\bshred\s+""",
                """\bunlink\s+""",
                // 移动/覆盖也可能把东西弄没
                """\bmv\s+[^\n]*\s/(system|vendor|data|sdcard|storage/emulated)\b""",
                // 清空文件
                """>\s*/dev/null\s*$""",
                """truncate\s+-s\s*0""",
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
            id = "outside_user_area",
            label = "改动 sdcard 之外的文件",
            note = "不在你自己的地盘里",
            consequence = "这个路径不在 /storage/emulated/0 或 /sdcard 下面。" +
                "动这里的东西可能影响系统或别的应用，改坏了不一定能恢复。",
            regexes = emptyList(),   // 这一条靠 checkWrite / checkCommandTargets 判，不走正则
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
            // 有些类别（比如"改动 sdcard 之外"）不走正则，是另外判的
            if (cat.regexes.isEmpty()) return@forEach
            cat.regexes.forEach { pattern ->
                val m = runCatching { Regex(pattern, RegexOption.IGNORE_CASE).find(c) }.getOrNull()
                if (m != null) return Hit(cat, m.value.trim())
            }
        }
        return null
    }

    /**
     * 用户自己的地盘。
     *
     * 用户的要求：**在这两个之外动任何文件都要问** ——
     * 因为 sdcard 里是他自己的照片文档，出事了看得见；系统目录出事了直接开不了机。
     */
    private val USER_AREAS = listOf(
        "/storage/emulated/0",
        "/sdcard",
        "/storage/self/primary",
        "/mnt/sdcard",
    )

    /** 这个绝对路径是不是在用户地盘之外 */
    fun isOutsideUserArea(path: String): Boolean {
        if (path.isBlank()) return false
        val p = path.lowercase()
        // 相对路径算"在沙箱里"，交给沙箱自身的围栏管
        if (!p.startsWith("/")) return false
        return USER_AREAS.none { p == it || p.startsWith(it + "/") }
    }

    private val SENSITIVE_ANY = listOf(
        "/system", "/vendor", "/product", "/boot", "/data/adb",
        "/data/system", "/data/misc", "/dev/block", "/proc/sys",
        "/sys/fs/selinux", "/init.rc",
    )

    /**
     * 改动文件要不要先问。
     *
     * 规则很简单：**只要不在 sdcard / /storage/emulated/0 底下，就得问**。
     */
    fun checkWrite(path: String): Hit? {
        if (path.isBlank()) return null
        if (!isOutsideUserArea(path)) return null

        val p = path.lowercase()
        val cat = CATEGORIES.firstOrNull { c ->
            c.id == "boot_config" && SENSITIVE_ANY.any { p.startsWith(it) }
        } ?: CATEGORIES.firstOrNull { it.id == "outside_user_area" } ?: CATEGORIES.first()

        return Hit(cat, "写文件：" + path)
    }

    /**
     * 命令里要改文件、而且改到了 sdcard 外头 —— 也该问。
     *
     * 这是"看命令文本猜"，不可能滴水不漏；但能拦住
     * `echo x > /system/build.prop`、`sed -i ... /vendor/...` 这类最常见的。
     */
    private val WRITE_VERBS = listOf(
        "rm ", "rmdir ", "mv ", "cp ", "chmod ", "chown ", "touch ",
        "mkdir ", "ln ", "truncate ", "dd ", "tee ", "sed -i", "unzip ",
    )

    private val ABS_PATH = Regex("(/[A-Za-z0-9_./\\-]+)")

    fun checkCommandTargets(command: String): Hit? {
        if (command.isBlank()) return null
        val c = command.lowercase()

        // 得先是在动文件
        if (WRITE_VERBS.none { c.contains(it) } && !c.contains(">")) return null

        val paths = ABS_PATH.findAll(command).map { it.groupValues[1] }
            .filter { it.length > 3 }
            .toList()
        if (paths.isEmpty()) return null

        val outside = paths.filter { isOutsideUserArea(it) }
        // /dev/null 这种别烦人
        val meaningful = outside.filterNot {
            it.startsWith("/dev/null") || it.startsWith("/dev/std")
        }
        if (meaningful.isEmpty()) return null

        val cat = CATEGORIES.firstOrNull { it.id == "outside_user_area" } ?: CATEGORIES.first()
        return Hit(cat, meaningful.first())
    }
    /** 不管什么工具，统一过一道 */
    fun risk(tool: String, args: JSONObject): Hit? = when (tool) {
        AgentTools.SHELL -> {
            val cmd = args.optString("command", "")
            // 先看是不是危险命令（rm / dd / mkfs 这些）；
            // 不是的话再看"它要改哪儿" —— 改到 sdcard 外头一样要问
            check(cmd) ?: checkCommandTargets(cmd)
        }
        AgentTools.WRITE -> checkWrite(args.optString("path", ""))
        else -> null
    }

    /**
     * 只判"要不要问"，不问**问了之后算不算危险**。
     * 给界面显示用：让用户能看出来"这次到底过没过检查"。
     */
    fun describe(hit: Hit?): String = hit?.category?.label ?: "无",

    /** 碰上危险动作怎么办 */
    enum class Policy(val id: String, val label: String, val summary: String) {
        Ask("ask", "每次都问", "执行前弹一下，你点同意才跑 —— 默认"),
        Allow("allow", "一律放行", "不再问，AI 说跑就跑（想清楚再选）"),
        Deny("deny", "一律拒绝", "危险动作直接挡掉，AI 会收到一条拒绝"),
    }
}
