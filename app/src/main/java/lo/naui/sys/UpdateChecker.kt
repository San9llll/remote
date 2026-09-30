package lo.naui.sys

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** GitHub Release 里那个 apk */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
)

data class UpdateInfo(
    val version: String,
    val notes: String,
    val asset: UpdateAsset?,
)

/**
 * 从 GitHub 上问最新版。
 *
 * 只认仓库里**最新那个 release**，APK 从 release 的 asset 直接下到本机
 * （不是点一下就跳浏览器），下完用 FileProvider 拉起系统安装器。
 */
object UpdateChecker {

    private const val API_PATH = "https://api.github.com/repos/San9llll/remote/releases/latest"

    /**
     * 直连 + 几个代理。实测：
     *   api.github.com          跑在 github 的域名上，国内经常**DNS 都解析不了**
     *                           （实机报：Unable to resolve host "api.github.com"）
     *   gh-proxy.com            能代理 API（实测 200，能拿到 releases/latest 的 JSON）
     *   另外两个只能代理文件下载，走 API 会 403，放着当备胎
     */
    private fun endpoints(): List<String> = listOf(
        API_PATH,
        "https://gh-proxy.com/$API_PATH",
        "https://ghfast.top/$API_PATH",
        "https://ghproxy.net/$API_PATH",
    )

    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            var lastErr: Throwable? = null
            for (api in endpoints()) {
                val r = runCatching { fetchOne(api) }
                if (r.isSuccess) return@runCatching r.getOrThrow()
                lastErr = r.exceptionOrNull()
            }
            throw IllegalStateException(lastErr?.message ?: "所有更新源都连不上")
        }
    }

    private fun fetchOne(api: String): UpdateInfo {
        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Nakour")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException(
                    api.substringAfter("//").substringBefore('/') + " 返回 " + code
                )
            }

            val json = JSONObject(body)
            val version = json.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")

            var asset: UpdateAsset? = null
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optString("name", "")
                    if (n.endsWith(".apk", ignoreCase = true)) {
                        asset = UpdateAsset(
                            name = n,
                            url = o.optString("browser_download_url", ""),
                            size = o.optLong("size", 0L),
                        )
                        break
                    }
                }
            }
            UpdateInfo(version, json.optString("body", ""), asset)
    }

    /** 版本号比大小：0.10.0 比 0.9.0 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 下载地址也备几个代理 —— 有些网络连 github.com 的 DNS 都解析不了 */
    private fun assetUrls(asset: UpdateAsset): List<String> = listOf(
        asset.url,
        "https://gh-proxy.com/" + asset.url,
        "https://ghfast.top/" + asset.url,
        "https://ghproxy.net/" + asset.url,
    )

    /** 下到 filesDir/update/ 下，边下边回报进度 */
    suspend fun download(
        ctx: Context,
        asset: UpdateAsset,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.filesDir, "update").apply { mkdirs() }
            val out = File(dir, asset.name)
            if (out.exists() && out.length() == asset.size && asset.size > 0) return@runCatching out

            var lastErr: Throwable? = null
            for (u in assetUrls(asset)) {
                val r = runCatching { downloadFrom(u, out, asset, onProgress) }
                if (r.isSuccess) return@runCatching out
                lastErr = r.exceptionOrNull()
                runCatching { out.delete() }
            }
            throw IllegalStateException(lastErr?.message ?: "下载失败")
        }
    }

    private fun downloadFrom(
        url: String,
        out: File,
        asset: UpdateAsset,
        onProgress: (Float) -> Unit,
    ) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Nakour")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("下载失败 HTTP " + code)
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            conn.disconnect()
            if (asset.size > 0 && out.length() < asset.size - 4096) {
                throw IllegalStateException("下到 " + (out.length() / 1024 / 1024) + "MB 就断了")
            }
            onProgress(1f)
    }

    /** 拉起系统安装器 */
    fun install(ctx: Context, file: File): Result<Unit> = runCatching {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
