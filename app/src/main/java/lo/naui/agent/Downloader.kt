package lo.naui.agent

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台下载。
 *
 * ## 为什么单独搞一个
 *
 * 以前下载只能靠 AI 发一条 `curl` 命令，然后**同步等它跑完**。
 * 而命令执行是有超时的 —— 大文件必然超时，AI 又不知道是"被掐了"，
 * 于是重试、再重试，实机上就出了"51 次工具、30 次思考还没下好"。
 *
 * 现在改成异步：
 *
 * ```
 * start_download(url, path)   → 立刻返回一个 id，不等
 * check_download(id)          → 想看进度就问一句
 * ```
 *
 * 下载跑在后台协程里，进度顺手喂给 [AgentTaskStore]，
 * 界面上那条进度条也能跟着动。
 */
object Downloader {

    data class Task(
        val id: String,
        val url: String,
        val path: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var done: Boolean = false,
        @Volatile var ok: Boolean = false,
        @Volatile var total: Long = 0L,
        @Volatile var got: Long = 0L,
        @Volatile var error: String = "",
    ) {
        val percent: Int
            get() = if (total > 0) ((got * 100) / total).toInt().coerceIn(0, 100)
            else if (done && ok) 100 else 0

        val speed: String
            get() {
                val sec = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(0.5)
                val bps = got / sec
                return human(bps) + "/s"
            }

        fun summary(): String = buildString {
            append("id=").append(id).append('\n')
            append("地址：").append(url).append('\n')
            append("存到：").append(path).append('\n')
            when {
                !done -> {
                    append("状态：下载中  ").append(percent).append("%")
                    if (total > 0) {
                        append("（").append(human(got.toDouble())).append(" / ")
                            .append(human(total.toDouble())).append("）")
                    }
                    append("  速度 ").append(speed)
                }
                ok -> append("状态：**下好了**  ").append(human(got.toDouble()))
                    .append("，用时 ").append((System.currentTimeMillis() - startedAt) / 1000).append(" 秒")
                else -> append("状态：失败了 —— ").append(error)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = java.util.concurrent.ConcurrentHashMap<String, Task>()
    private var seq = 0

    /** 按 id 取（AI 查进度用） */
    fun get(id: String): Task? = tasks[id]

    /** 最近那几个，给"我发过什么下载"用 */
    fun recent(): List<Task> =
        tasks.values.sortedByDescending { it.startedAt }.take(10)

    /**
     * 起一个下载，**立刻返回**。
     *
     * 路径是相对沙箱根算的 —— 要存到 sdcard 就传绝对路径。
     */
    fun start(ctx: Context, url: String, path: String): Task {
        val id = "dl" + (++seq) + "_" + (System.currentTimeMillis() % 100000)
        val t = Task(id = id, url = url, path = path)
        tasks[id] = t

        scope.launch {
            runCatching {
                val out = File(path)
                out.parentFile?.mkdirs()

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Nakour")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP " + code)
                }

                t.total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L

                // 断点续传：本地已经有的话，从那儿接着下
                val already = if (out.exists()) out.length() else 0L
                if (already > 0 && t.total > already) {
                    conn.disconnect()
                    val conn2 = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Nakour")
                        setRequestProperty("Range", "bytes=" + already + "-")
                    }
                    conn2.inputStream.use { input ->
                        FileOutputStream(out, true).use { output ->
                            pipe(input, output, t, already)
                        }
                    }
                    conn2.disconnect()
                } else {
                    conn.inputStream.use { input ->
                        FileOutputStream(out, false).use { output ->
                            pipe(input, output, t, 0L)
                        }
                    }
                    conn.disconnect()
                }

                t.ok = true
                t.done = true
                // 界面上那条进度条也收掉
                AgentTaskStore.updateToolProgress(1f)
                AgentTaskStore.updateToolSpeed("")
            }.onFailure { e ->
                t.done = true
                t.ok = false
                t.error = e.message ?: e.javaClass.simpleName
            }
        }

        return t
    }

    private fun pipe(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        t: Task,
        base: Long,
    ) {
        // 续传的时候，已经下好的那部分也要算进去
        if (base > 0) t.got = base

        val buf = ByteArray(128 * 1024)
        var n: Int
        var lastReport = 0L
        while (true) {
            n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            t.got += n

            // 每 400ms 往界面上报一次，别刷太勤
            val now = System.currentTimeMillis()
            if (now - lastReport > 400) {
                lastReport = now
                if (t.total > 0) {
                    AgentTaskStore.updateToolProgress((t.got.toFloat() / t.total).coerceIn(0f, 1f))
                }
                AgentTaskStore.updateToolSpeed(t.speed)
            }
        }
    }

    /* ================= 小工具 ================= */

    private fun human(b: Double): String = when {
        b >= 1024 * 1024 * 1024 -> String.format("%.2f GB", b / 1024 / 1024 / 1024)
        b >= 1024 * 1024 -> String.format("%.1f MB", b / 1024 / 1024)
        b >= 1024 -> String.format("%.1f KB", b / 1024)
        else -> String.format("%.0f B", b)
    }
}
