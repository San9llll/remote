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
import lo.naui.agent.ChatDb
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

    /**
     * 每个会话一个协程。
     *
     * 以前是单个 `job`，来新任务就 `job?.cancel()` ——
     * 结果在 A 会话思考时切到 B 发消息，A 直接被掐了。
     * 用户要的是**两边各跑各的**，所以改成按会话存。
     */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN -> {
                startForeground(NOTIFY_ID, buildNotification("正在想…"))
                val conv = intent.getStringExtra(EXTRA_CONV).orEmpty()
                // **不 cancel 别人** —— 各跑各的
                jobs[conv]?.cancel()
                jobs[conv] = scope.launch {
                    try {
                        runTask(intent)
                    } finally {
                        jobs.remove(conv)
                        // 一个都不剩了就撤，别一直占着通知
                        if (jobs.isEmpty()) stopSelf()
                    }
                }
            }

            ACTION_STOP -> {
                val conv = intent.getStringExtra(EXTRA_CONV).orEmpty()
                if (conv.isBlank()) jobs.values.forEach { it.cancel() } else jobs[conv]?.cancel()
                if (jobs.isEmpty()) stopSelf()
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

        // ⚠️ 历史**不能**塞在 Intent 里 —— Binder 有 1MB 上限，对话一长就抛
        // TransactionTooLargeException，然后状态永远卡在"在想"。
        // 所以只传会话 id，历史让它自己去本地读。
        ChatDb.init(this)
        val history = ChatDb.load(convId).map { ChatMessage(it.role, it.text) }

        val result = AgentChat.run(
            ctx = this,
            conversationId = convId,
            system = system,
            history = history,
            env = env,
            maxTokens = maxTokens,
            temperature = temperature,
            onProgress = { p ->
                AgentTaskStore.progress(convId, p)
                notify(buildNotification(p))
            },
            // 边想边说：三种增量分别往 Store 上堆，界面实时读。
            // 都带 convId —— 这样切到别的会话时，这里的更新不会串台。
            onReasoning = { r -> AgentTaskStore.appendReasoning(convId, r) },
            onDelta = { d -> AgentTaskStore.appendText(convId, d) },
            onRound = { n -> AgentTaskStore.setRounds(convId, n) },
            // 危险动作：把请求挂到 Store 上，等界面弹窗让用户点
            askUser = { hit ->
                val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
                AgentTaskStore.pendingConfirm = AgentTaskStore.ConfirmRequest(convId, hit, gate)
                AgentTaskStore.progress(convId, "等你确认：" + hit.category.label)
                notify(buildNotification("等你确认：" + hit.category.label))

                // 最多等 90 秒 —— 用户可能压根没看手机，别一直吊着
                val ok = kotlinx.coroutines.withTimeoutOrNull(90_000L) { gate.await() } ?: false
                AgentTaskStore.pendingConfirm = null
                if (ok) {
                    AgentTaskStore.progress(convId, "你同意了，继续…")
                } else {
                    AgentTaskStore.progress(convId, "没等到确认，跳过这个动作")
                }
                ok
            },
        )

        try {
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
                        reasoning = run.reasoning,
                        steps = run.steps,
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
        } finally {
            // 不管中间怎么炸的，状态一定要收尾 —— 不然界面就永远停在"在想"
            if (AgentTaskStore.stateOf(convId).running) {
                AgentTaskStore.finish(
                    AgentTaskStore.Result(
                        conversationId = convId,
                        reply = "",
                        toolLog = emptyList(),
                        thinkRounds = 0,
                        usage = AgentApi.Usage(),
                        ok = false,
                        error = "任务中断了",
                    )
                )
            }
            // 歇一会儿再撤，别一直占着通知
            kotlinx.coroutines.delay(4000)
            stopSelf()
        }
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
        jobs.values.forEach { it.cancel() }
        jobs.clear()
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
        ) {
            AgentTaskStore.begin(conversationId)

            // Intent 里只放小东西（id 和几个配置），历史由服务自己去读
            val intent = Intent(ctx, AgentTaskService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_CONV, conversationId)
                putExtra(EXTRA_SYSTEM, system)
                putExtra(EXTRA_ENV, env.id)
                putExtra(EXTRA_MAX_TOKENS, maxTokens)
                putExtra(EXTRA_TEMP, temperature)
            }

            val r = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }

            // 起不来就把状态回滚 —— 不然界面会一直转"在想"
            r.onFailure { e ->
                AgentTaskStore.finish(
                    AgentTaskStore.Result(
                        conversationId = conversationId,
                        reply = "",
                        toolLog = emptyList(),
                        thinkRounds = 0,
                        usage = AgentApi.Usage(),
                        ok = false,
                        error = "后台服务起不来：" + (e.message ?: e.javaClass.simpleName),
                    )
                )
            }
        }
    }
}
