package lo.naui.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 托着 Agent 任务的前台服务。
 *
 * 为什么要它：用户发一个要想很久的任务，切去别页或者切后台，
 * 普通协程会被系统顺手清掉。挂上前台服务（带通知）之后进程优先级上去，
 * 任务就能一直跑到出结果。
 *
 * 状态都写在 [AgentTaskStore] 里，界面只是读它 ——
 * 所以切页不会丢，回来能接着看"还在想第几轮"。
 */
class AgentTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN -> {
                startForeground(NOTIFY_ID, buildNotification("正在想…"))
                job?.cancel()
                job = scope.launch { runTask(intent) }
            }

            ACTION_STOP -> {
                job?.cancel()
                stopSelf()
            }

            else -> startForeground(NOTIFY_ID, buildNotification("待命"))
        }
        // 被系统杀了以后回来还活着
        return START_STICKY
    }

    private suspend fun runTask(intent: Intent) {
        val convId = intent.getStringExtra(EXTRA_CONV) ?: return
        val system = intent.getStringExtra(EXTRA_SYSTEM).orEmpty()
        val env = AgentEnv.of(intent.getStringExtra(EXTRA_ENV))
        val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 1_000_000)
        val temperature = intent.getFloatExtra(EXTRA_TEMP, 0.7f)
        val historyJson = intent.getStringExtra(EXTRA_HISTORY).orEmpty()

        val history = runCatching {
            val arr = org.json.JSONArray(historyJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(o.optString("role", "user"), o.optString("text", ""))
            }
        }.getOrDefault(emptyList())

        val result = AgentChat.run(
            ctx = this,
            system = system,
            history = history,
            env = env,
            maxTokens = maxTokens,
            temperature = temperature,
            onProgress = { p ->
                AgentTaskStore.progress(p)
                notify(buildNotification(p))
            },
            // 危险动作要问用户 —— 后台跑的时候没法弹窗，所以先拒掉，
            // 让模型知道"现在没人确认"，它自己会换办法
            askUser = { hit ->
                // 后台跑的时候没法弹窗，先拒掉，让模型知道"这会儿没人确认"
                AgentTaskStore.progress("需要你确认（" + hit.category.label + "）—— 回聊天页重发一次吧")
                false
            },
        )

        result
            .onSuccess { run ->
                AgentTaskStore.finish(
                    AgentTaskStore.Result(
                        conversationId = convId,
                        reply = run.reply,
                        toolLog = run.toolLog,
                        thinkRounds = run.thinkRounds,
                        usage = run.usage,
                        ok = true,
                    )
                )
                notify(buildNotification("想完了"))
            }
            .onFailure { e ->
                AgentTaskStore.finish(
                    AgentTaskStore.Result(
                        conversationId = convId,
                        reply = "",
                        toolLog = emptyList(),
                        thinkRounds = 0,
                        usage = AgentApi.Usage(),
                        ok = false,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                )
                notify(buildNotification("出错了：" + (e.message ?: "").take(40)))
            }

        // 弄完了歇一会儿就撤，别一直占着通知
        kotlinx.coroutines.delay(4000)
        stopSelf()
    }

    /* ---------------- 通知 ---------------- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Agent 任务", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "跑 Agent 任务时的常驻通知" }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val open = Intent(this, lo.naui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 0, open, flags)

        return builder
            .setContentTitle("Nakour")
            .setContentText(text.take(80))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notify(n: Notification) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, n)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "nakour_agent_task"
        private const val NOTIFY_ID = 0x4E41

        const val ACTION_RUN = "lo.naui.agent.RUN"
        const val ACTION_STOP = "lo.naui.agent.STOP"

        private const val EXTRA_CONV = "conv"
        private const val EXTRA_SYSTEM = "system"
        private const val EXTRA_ENV = "env"
        private const val EXTRA_MAX_TOKENS = "max_tokens"
        private const val EXTRA_TEMP = "temp"
        private const val EXTRA_HISTORY = "history"

        /**
         * 把任务交给服务去跑。
         *
         * [history] 是之前的对话（不含刚发的那条 —— 服务会自己拼）。
         */
        fun start(
            ctx: Context,
            conversationId: String,
            system: String,
            env: AgentEnv,
            maxTokens: Int,
            temperature: Float,
            history: List<ChatMessage>,
        ) {
            val arr = org.json.JSONArray()
            history.forEach { m ->
                arr.put(org.json.JSONObject().put("role", m.role).put("text", m.text))
            }

            AgentTaskStore.begin(conversationId)

            val intent = Intent(ctx, AgentTaskService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_CONV, conversationId)
                putExtra(EXTRA_SYSTEM, system)
                putExtra(EXTRA_ENV, env.id)
                putExtra(EXTRA_MAX_TOKENS, maxTokens)
                putExtra(EXTRA_TEMP, temperature)
                putExtra(EXTRA_HISTORY, arr.toString())
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        }
    }
}
