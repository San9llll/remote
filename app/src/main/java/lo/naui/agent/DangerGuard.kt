package lo.naui.agent

import org.json.JSONObject

/**
 * 危险动作的闸门。
 *
 * AI 手里有 root，一句 `rm -rf /` 就能把手机变砖。
 * 所以凡是碰到下面这些花样，**执行前必须让人点一下同意**。
 *
 * 这不是"禁止"，只是"先问一句" —— 用户明确要能真操作底层，
 * 那就别拦死，但也不能闭眼放行。
 */
object DangerGuard {

    /** 命令里出现这些就算危险 */
    private val DANGEROUS = listOf(
        // 删
        "rm -rf", "rm -fr", "rm -f", "rm -r", "rmdir", "shred",
        // 提权
        "su ", "su-", "sudo", "setenforce", "chmod 777 /", "chown -r /",
        // 磁盘 / 分区
        "dd ", "mkfs", "fdisk", "wipe", "format ", "> /dev/block", "of=/dev/block",
        // 系统级
        "reboot", "shutdown", "fastboot", "flash ", "oem unlock",
        "pm uninstall", "pm disable", "pm clear", "pm hide",
        "am force-stop", "stop ", "start ",
        // 网络 / 防火墙
        "iptables", "nft ", "ifconfig", "ip link",
        // 经典
        ":(){", "mv /*", "cp /*", "> /system", "> /data",
    )

    /** 写文件时碰到这些目录也要问一下 */
    private val SENSITIVE_PATHS = listOf(
        "/system", "/vendor", "/product", "/boot", "/data/adb",
        "/data/system", "/data/misc", "/dev/block", "/proc/sys",
    )

    /** 这条命令要不要先问 */
    fun checkCommand(command: String): String? {
        val c = command.lowercase().replace(Regex("\\s+"), " ")
        DANGEROUS.firstOrNull { c.contains(it) }?.let { hit ->
            return when {
                hit.startsWith("rm") -> "要删东西（" + hit + "）"
                hit.startsWith("su") || hit == "sudo" -> "要提权（" + hit.trim() + "）"
                hit.contains("dev/block") || hit.startsWith("dd") || hit.startsWith("mkfs") ->
                    "要动磁盘分区（" + hit.trim() + "）"
                hit.contains("pm ") -> "要动已安装的应用（" + hit.trim() + "）"
                else -> "包含危险操作（" + hit.trim() + "）"
            }
        }
        return null
    }

    /** 写文件要不要先问 */
    fun checkWrite(path: String): String? {
        val p = path.lowercase()
        SENSITIVE_PATHS.firstOrNull { p.startsWith(it) }?.let {
            return "要往系统目录写东西（" + it + "）"
        }
        return null
    }

    /** 碰上危险动作怎么办 */
    enum class Policy(val id: String, val label: String, val summary: String) {
        Ask("ask", "每次都问", "执行前弹一下，你点同意才跑 —— 默认"),
        Allow("allow", "一律放行", "不再问，AI 说跑就跑（想清楚再选）"),
        Deny("deny", "一律拒绝", "危险动作直接挡掉，AI 会收到一条拒绝"),
    }

    /**
     * 不管什么工具，统一在这儿过一道。
     * 返回 null = 直接放行；返回字符串 = 先让用户点头。
     */
    fun risk(tool: String, args: JSONObject): String? = when (tool) {
        AgentTools.SHELL -> checkCommand(args.optString("command", ""))
        AgentTools.WRITE -> checkWrite(args.optString("path", ""))
        AgentTools.READ, AgentTools.LIST -> null
        else -> null
    }
}
