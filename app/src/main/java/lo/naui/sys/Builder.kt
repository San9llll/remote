package lo.naui.sys

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 内置的 GitHub 构建。
 *
 * ## 为什么要在 App 里做这个
 *
 * Android 上**没法真的编译 Android 项目** —— 没有 JDK、没有 aapt2、没有 d8，
 * 这些东西体积也搬不进来。所以"内置编译"的可行形态是：
 *
 * ```
 * App 点一下 → 调 GitHub Actions 的 workflow_dispatch → 那边真编
 *            → 轮询状态 → 编完了直接下 APK / artifact
 * ```
 *
 * 用户要的就是这个：**不用开电脑，手机上点两下就能出新包**。
 *
 * ## 要什么
 *
 * 一个 GitHub PAT（细粒度 token），权限只需要：
 *   · `Actions: Read and write`  ← 触发和查状态
 *   · `Contents: Read`           ← 下 release
 *
 * 填在设置里，存本机。
 */
object Builder {

    private const val REPO = "San9llll/remote"
    private const val API = "https://api.github.com/repos/" + REPO
    private const val WORKFLOW = "build.yml"

    /* ================= 配置 ================= */

    private var sp: android.content.SharedPreferences? = null

    var token by mutableStateOf("")
        private set

    /** 上次触发的是哪个 run id（拿它查进度） */
    var lastRunId by mutableStateOf(0L)
        private set

    var lastTag by mutableStateOf("")
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("nakour_build", Context.MODE_PRIVATE)
        sp = p
        token = p.getString("token", "") ?: ""
        lastRunId = p.getLong("last_run", 0L)
        lastTag = p.getString("last_tag", "") ?: ""
    }

    fun updateToken(ctx: Context, v: String) {
        init(ctx)
        token = v.trim()
        sp?.edit()?.putString("token", token)?.apply()
    }

    private fun remember(ctx: Context, runId: Long, tag: String) {
        init(ctx)
        lastRunId = runId
        lastTag = tag
        sp?.edit()?.putLong("last_run", runId)?.putString("last_tag", tag)?.apply()
    }

    val ready: Boolean get() = token.isNotBlank()

    /* ================= 数据结构 ================= */

    data class Run(
        val id: Long,
        val status: String,      // queued / in_progress / completed
        val conclusion: String,  // success / failure / ""（还在跑）
        val sha: String = "",
        val branch: String = "",
        val at: String = "",
    ) {
        val running: Boolean get() = status != "completed"
        val ok: Boolean get() = conclusion == "success"
        val text: String
            get() = when {
                running -> if (status == "queued") "排队中" else "正在编…"
                ok -> "编好了"
                else -> "失败了（" + conclusion + "）"
            }
    }

    /* ================= 请求 ================= */

    private fun conn(url: String, method: String, body: String? = null): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 40_000
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Nakour")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) runCatching { c.outputStream.use { it.write(body.toByteArray()) } }
        return c
    }

    private fun read(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to body
    }

    /* ================= 触发构建 ================= */

    /**
     * 点一下就开始编。
     *
     * 用 `workflow_dispatch` —— 这要求 workflow 文件里有 `workflow_dispatch:`
     * 这个触发器（build.yml 里已经有了）。
     *
     * 返回的 run id 用来查进度。
     */
    suspend fun trigger(ctx: Context, tag: String = ""): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            if (!ready) throw IllegalStateException("还没填 GitHub Token")

            // 打 tag 的话先建 tag（这样 CI 那边会出 Release）
            val realTag = tag.trim().ifBlank {
                "v" + (System.currentTimeMillis() / 60000)
            }

            val body = JSONObject()
                .put("ref", "main")
                .put(
                    "inputs",
                    JSONObject().put("tag", realTag),
                )
                .toString()

            val (code, resp) = read(conn("$API/actions/workflows/$WORKFLOW/dispatches", "POST", body))
            if (code !in 200..299) {
                throw IllegalStateException("触发失败 HTTP $code：" + resp.take(200))
            }

            // dispatch 接口不返回 run id，等一下再查最新的
            kotlinx.coroutines.delay(3000)
            val r = latest()
            val id = r?.id ?: 0L
            remember(ctx, id, realTag)
            id
        }
    }

    /* ================= 查状态 ================= */

    /** 最近一次构建 */
    suspend fun latest(): Run? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = read(conn("$API/actions/runs?per_page=1", "GET"))
            if (code !in 200..299) return@runCatching null
            val arr = JSONObject(body).optJSONArray("workflow_runs") ?: return@runCatching null
            if (arr.length() == 0) return@runCatching null
            val o = arr.optJSONObject(0) ?: return@runCatching null
            Run(
                id = o.optLong("id"),
                status = o.optString("status", ""),
                conclusion = o.optString("conclusion", ""),
                sha = o.optString("head_sha", "").take(7),
                branch = o.optString("head_branch", ""),
                at = o.optString("created_at", ""),
            )
        }.getOrNull()
    }

    /* ================= 拿产物 ================= */

    /** 最近一次 release 的 APK 下载地址 */
    suspend fun latestApkUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = read(conn("$API/releases/latest", "GET"))
            if (code !in 200..299) return@runCatching null
            val assets = JSONObject(body).optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.endsWith(".apk", true)) return@runCatching a.optString("browser_download_url", "")
            }
            null
        }.getOrNull()
    }

    /**
     * 把 APK 下到本机。
     *
     * 走 UpdateChecker 那套代理链（有些网络连 github.com 的 DNS 都解析不了）。
     */
    suspend fun downloadApk(
        ctx: Context,
        url: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.filesDir, "update").apply { mkdirs() }
            val out = File(dir, url.substringAfterLast('/').ifBlank { "nakour.apk" })

            val urls = listOf(
                url,
                "https://gh-proxy.com/$url",
                "https://ghfast.top/$url",
            )

            var lastErr: Throwable? = null
            for (u in urls) {
                val r = runCatching {
                    val c = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Nakour")
                    }
                    val code = c.responseCode
                    if (code !in 200..299) {
                        c.disconnect()
                        throw IllegalStateException("HTTP " + code)
                    }
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: (20L * 1024 * 1024)
                    c.inputStream.use { input ->
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                done += n
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    c.disconnect()
                    if (out.length() < 1024L * 1024L) {
                        throw IllegalStateException("只有 " + (out.length() / 1024) + "KB，不像 APK")
                    }
                }
                if (r.isSuccess) return@runCatching out
                lastErr = r.exceptionOrNull()
                runCatching { out.delete() }
            }
            throw IllegalStateException(lastErr?.message ?: "下载失败")
        }
    }
}
