package lo.naui.term

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 一个终端会话。
 *
 * 底下是一个真 pty：native 那边 forkpty 出来的，所以 isatty() 为真、
 * ls 会带颜色、vim/top 能认得出屏幕尺寸。
 */
class TerminalSession(
    val shellPath: String,
    val cwd: String,
    val env: Array<String>,
    rows: Int,
    cols: Int,
    palette: IntArray = TerminalColor.BASE16,
    defaultFg: Int = TerminalColor.DEFAULT_FG,
    defaultBg: Int = TerminalColor.DEFAULT_BG,
    maxScrollback: Int = 4000,
) {

    val emulator = TerminalEmulator(rows, cols, palette, defaultFg, defaultBg, maxScrollback) { s -> write(s) }

    @Volatile var pid: Int = -1
        private set

    @Volatile var running: Boolean = false
        private set

    @Volatile var exitCode: Int = -1
        private set

    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private var waiter: Thread? = null

    private val closing = AtomicBoolean(false)

    /** 有输出 / 退出时回调（用来触发重绘） */
    var onOutput: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    /** 终端窗口多大（像素），resize 时一起告诉内核 */
    var cellWidthPx: Int = 0
    var cellHeightPx: Int = 0

    fun start(): Boolean {
        if (running) return true
        val pidOut = IntArray(1)
        val fd = try {
            TerminalNative.createSubprocess(
                cmd = arrayOf(shellPath),
                cwd = cwd,
                env = env,
                pidOut = pidOut,
                rows = emulator.rows,
                cols = emulator.cols,
            )
        } catch (e: Throwable) {
            -1
        }
        if (fd < 0) return false

        pid = pidOut[0]
        return try {
            val descriptor = ParcelFileDescriptor.adoptFd(fd)
            pfd = descriptor
            input = FileInputStream(descriptor.fileDescriptor)
            output = FileOutputStream(descriptor.fileDescriptor)
            running = true
            closing.set(false)
            startReader()
            startWaiter()
            true
        } catch (e: Throwable) {
            runCatching { pfd?.close() }
            false
        }
    }

    private fun startReader() {
        reader = thread(name = "term-reader", isDaemon = true) {
            val buf = ByteArray(8192)
            try {
                while (!closing.get()) {
                    val n = input?.read(buf) ?: break
                    if (n <= 0) break
                    emulator.append(buf, n)
                    onOutput?.invoke()
                }
            } catch (e: Throwable) {
                // 关掉的时候这里抛异常是正常的
            }
            running = false
            onOutput?.invoke()
            onExit?.invoke()
        }
    }

    private fun startWaiter() {
        waiter = thread(name = "term-waiter", isDaemon = true) {
            val code = try {
                TerminalNative.waitFor(pid)
            } catch (e: Throwable) {
                -1
            }
            exitCode = code
            running = false
            onExit?.invoke()
        }
    }

    /** 把用户输入写进 pty */
    fun write(data: String) {
        val out = output ?: return
        try {
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Throwable) {
            // 进程可能已经没了
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        emulator.resize(rows, cols)
        val fd = pfd?.fd ?: return
        runCatching {
            TerminalNative.setPtyWindowSize(fd, rows, cols, cellWidthPx, cellHeightPx)
        }
    }

    /** Ctrl+C：发给整个前台进程组，别只杀 shell */
    fun sendCtrlC() {
        if (pid <= 0) return
        runCatching { TerminalNative.sendSignalToGroup(pid, 2) }   // SIGINT
    }

    /** Ctrl+Z */
    fun sendCtrlZ() {
        if (pid <= 0) return
        runCatching { TerminalNative.sendSignalToGroup(pid, 20) }  // SIGTSTP
    }

    fun close() {
        closing.set(true)
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { pfd?.close() }
        if (pid > 0) {
            runCatching { TerminalNative.sendSignal(pid, 9) }      // SIGKILL
        }
        running = false
    }

    /** 主目录 —— 进终端时先 cd 到这里 */
    companion object {
        fun defaultEnv(filesDir: File): Array<String> {
            val home = File(filesDir, "home").apply { mkdirs() }
            val tmp = File(filesDir, "tmp").apply { mkdirs() }
            return arrayOf(
                "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/product/bin",
                "HOME=" + home.absolutePath,
                "TMPDIR=" + tmp.absolutePath,
                "SHELL=/system/bin/sh",
                "USER=root",
                "LOGNAME=root",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=en_US.UTF-8",
            )
        }
    }
}
