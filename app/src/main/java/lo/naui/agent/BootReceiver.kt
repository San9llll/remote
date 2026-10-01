package lo.naui.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启。
 *
 * 只做一件事：把 [AgentTaskService] 拉起来待命。
 * 开关在设置里（默认关着 —— 没人喜欢装个 app 就自己开机跑东西）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        AgentTaskStore.init(context)
        if (!AgentTaskStore.bootStart) return

        runCatching {
            val i = Intent(context, AgentTaskService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
