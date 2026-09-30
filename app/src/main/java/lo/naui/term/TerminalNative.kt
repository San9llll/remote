package lo.naui.term

/**
 * native 层的门面。真正干活的是 cpp/terminal_jni.c。
 *
 * 返回的 fd 是 PTY 的 master 端，交给 [TerminalSession] 用流去读写。
 */
object TerminalNative {

    init {
        System.loadLibrary("nakourterm")
    }

    /** 开一个会话，返回 master fd；pid 写进 pidOut[0] */
    external fun createSubprocess(
        cmd: Array<String>,
        cwd: String?,
        env: Array<String>?,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int, cellWidth: Int, cellHeight: Int)

    external fun waitFor(pid: Int): Int

    /** 给整个前台进程组发信号（Ctrl+C 用这个，负数 pid） */
    external fun sendSignalToGroup(pid: Int, sig: Int)

    external fun sendSignal(pid: Int, sig: Int)

    external fun isatty(fd: Int): Boolean
}
