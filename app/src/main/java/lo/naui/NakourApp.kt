package lo.naui

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获（本地版）。
 *
 * 装在 Application 里，任何线程没接住的异常都会先被这里记一笔，
 * 写到外部私有目录 `Android/data/<包名>/files/crash.txt`（文件管理器能拿到），
 * 最后交回系统默认处理，让进程照常退出。
 *
 * handler 装在 attachBaseContext（比 onCreate 更早），这样连
 * Application.onCreate 里抛的异常也能记到。
 */
class NakourApp : Application() {

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { record(t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    private fun record(t: Thread, e: Throwable) {
        val text = buildString {
            append("time   : ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            append("app    : ")
            append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("android: ")
            append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("device : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("thread : ").append(t.name).append('\n')
            append("---- stack ----\n")
            append(Log.getStackTraceString(e))
        }

        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "crash.txt").writeText(text)
        }
    }
}
