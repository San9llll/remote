package lo.naui.agent

/**
 * 从命令输出里认下载进度。
 *
 * curl 和 wget 的格式差挺多，两种都要认：
 *
 * ```
 * # curl（--progress-bar）
 * ##############            45.2%
 *
 * # curl（默认表格）
 *   % Total    % Received % Xferd  Average Speed
 *  45  4.7M   45  2.1M    0     0  1024k      0  0:00:02  0:00:01  0:00:01 1024k
 *
 * # wget（--show-progress）
 * 45% [==========>          ] 4,723,456  3.21MB/s   eta 2s
 * ```
 *
 * ⚠️ curl 在输出被重定向（不是终端）时**默认不打进度**，所以系统提示词里
 * 会提醒它下大文件时带上 `--progress-bar` / `--show-progress`。
 */
object Progress {

    /** 45% / 45.2% */
    private val PERCENT = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%")

    /** 进度条形式：##########  45 */
    private val BAR = Regex("#{3,}\\s*(\\d{1,3})")

    /** 速度：3.2MB/s、1024k、1.5M */
    private val SPEED = Regex("(\\d+(?:[.,]\\d+)?)\\s*([KMGT]?)i?B?(?:/s)?", RegexOption.IGNORE_CASE)

    /** 从一行里抠出 0~1 的进度；抠不到返回 null */
    fun parse(line: String): Float? {
        if (line.isBlank()) return null
        val m = PERCENT.find(line) ?: BAR.find(line) ?: return null
        val v = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
        if (v < 0f || v > 100f) return null
        return v / 100f
    }

    /**
     * 从一行里抠出速度，凑成一句人话。
     *
     * 优先找带 `/s` 的；找不到就找末尾那个带单位的数
     * （curl 表格最后一列就是速度）。
     */
    fun speed(line: String): String? {
        if (line.isBlank()) return null
        val lower = line.lowercase()
        val looksLikeProgress = lower.contains("/s") || lower.contains("eta") ||
            lower.contains("speed") || lower.contains("xferd")

        // 1) 明确带 /s 的
        for (m in SPEED.findAll(line).toList().reversed()) {
            val num = m.groupValues[1].replace(',', '.')
            val unit = m.groupValues[2].uppercase()
            val after = line.substring(m.range.last + 1).trimStart()
            if (after.startsWith("/s", true) || after.startsWith("b/s", true)) {
                return num + " " + (if (unit.isBlank()) "B" else unit + "B") + "/s"
            }
        }

        // 2) curl 表格 / wget 那种，末尾带单位的
        if (looksLikeProgress) {
            val ms = SPEED.findAll(line).toList()
            for (m in ms.reversed()) {
                val num = m.groupValues[1].replace(',', '.')
                val unit = m.groupValues[2].uppercase()
                if (num.isBlank()) continue
                if (unit.isBlank()) continue          // 没单位的多半是百分比，跳过
                return num + " " + unit + "B/s"
            }
        }
        return null
    }
}
