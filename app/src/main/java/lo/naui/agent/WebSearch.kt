package lo.naui.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 *联网搜索。
 *
 *用户要的：「Agent设置内新增是否启用联网搜索功能」。
 *关掉的时候这个工具**根本不发给模型**（不是发了再拒绝）——
 *少一个工具定义，提示词短一截，模型也不会惦记着去搜。
 *
 *为什么是自己抓 HTML而不是调搜索 API：
 *这台机器上实测通的只有 cn.bing.com和 www.baidu.com（Google / DuckDuckGo超时，
 * r.jina.ai也超时），而这两家都要注册才给 API。所以走网页抓取，
 *只抽标题和链接 ——摘要那一栏各家的选择器不一样，抓多了更容易崩。
 *
 * ⚠️结果里只有「标题 +链接」，正文正文得让模型再用 run_shell去 curl。
 *不要在这儿顺手把正文也抓了：一次搜索十条结果就是十次网络请求，
 *十几秒起步，用户等的是一条搜索而不是一个爬虫。
 */
object WebSearch {

 const val NAME = "web_search"

 const val ENGINE_BING = "bing"
 const val ENGINE_BAIDU = "baidu"

 /**伪装成桌面浏览器的 UA ——手机 UA抓回来的百度页面结构不一样 */
 /** HTTP成功区间。写成常量是为了躲开「空格+数字」被吃掉那个坑 */
 private val OK_RANGE =200..299

 private const val UA =
 "Mozilla/5.0 (Windows NT10.0; Win64; x64) AppleWebKit/537.36 " +
 "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

 suspend fun search(query: String, limit: Int, engine: String): Result<String> =
 withContext(Dispatchers.IO) {
 runCatching {
 val q = query.trim()
 if (q.isBlank()) return@runCatching "搜什么？query是空的"

 val n = limit.coerceIn(1,15)
 val url = when (engine.lowercase()) {
 ENGINE_BAIDU ->
 "https://www.baidu.com/s?ie=utf-8&wd=" + URLEncoder.encode(q, "UTF-8")
 else ->
 "https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8")
 }

 val html = fetch(url)
 val hits = if (engine.lowercase() == ENGINE_BAIDU) parseBaidu(html) else parseBing(html)

 if (hits.isEmpty()) {
 //抓不到多半是被挡了或者页面结构变了 ——把这件事说清楚，
 //而不是回一句"没搜到"让模型反复换关键词重试
 return@runCatching "没抓到结果。可能是被拦了、或者关键词太偏。" +
 "换个说法再试一次；连着两次拿不到就别搜了，直接按你知道的说，并说明没能联网核实。"
 }

 val sb = StringBuilder()
 sb.append("联网搜索：").append(q)
 sb.append("（来源 ").append(if (engine.lowercase() == ENGINE_BAIDU) "百度" else "必应")
 sb.append("，共 ").append(hits.size).append("条）\n")
 hits.take(n).forEachIndexed { i, h ->
 sb.append("\n").append(i +1).append(". ").append(h.title)
 if (h.url.isNotBlank()) sb.append("\n ").append(h.url)
 if (h.snippet.isNotBlank()) sb.append("\n ").append(h.snippet)
 }
 sb.append("\n\n（只有标题和链接，正文要自己 run_shell curl下来读。）")
 sb.toString()
 }
 }

 private data class Hit(val title: String, val url: String, val snippet: String)

 /* ----------------抓 ---------------- */

 private fun fetch(url: String): String {
 val conn = (URL(url).openConnection() as HttpURLConnection).apply {
 requestMethod = "GET"
 connectTimeout =12_000
 readTimeout =12_000
 instanceFollowRedirects = true
 setRequestProperty("User-Agent", UA)
 setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
 setRequestProperty("Accept", "text/html,application/xhtml+xml")
 }
 return try {
 val code = conn.responseCode
 val stream = if (code in OK_RANGE) conn.inputStream else conn.errorStream
 val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
 if (code !in OK_RANGE) {
 throw IllegalStateException("HTTP " + code + "，抓不下来")
 }
 body
 } finally {
 conn.disconnect()
 }
 }

 /* ----------------解 ---------------- */

 /**必应：结果块里 `<h2><a href="...">标题</a>` */
 private fun parseBing(html: String): List<Hit> {
 val out = mutableListOf<Hit>()
 val re = Regex(
 "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
 setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
 )
 re.findAll(html).forEach { m ->
 val url = clean(m.groupValues[1])
 val title = plain(m.groupValues[2])
 if (title.isNotBlank() && url.startsWith("http")) {
 out += Hit(title, url, "")
 }
 }
 //摘要：<p>那一段，跟标题分开抓，能配上就配上
 if (out.isNotEmpty()) {
 val ps = Regex("<p[^>]*>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
 .findAll(html).map { plain(it.groupValues[1]) }.filter { it.length >20 }.toList()
 return out.mapIndexed { i, h -> h.copy(snippet = ps.getOrElse(i) { "" }) }
 }
 return out
 }

 /**百度：`<h3 ...><a href="...">标题</a>` */
 private fun parseBaidu(html: String): List<Hit> {
 val out = mutableListOf<Hit>()
 val re = Regex(
 "<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
 setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
 )
 re.findAll(html).forEach { m ->
 val url = clean(m.groupValues[1])
 val title = plain(m.groupValues[2])
 if (title.isNotBlank() && url.startsWith("http")) out += Hit(title, url, "")
 }
 return out
 }

 /**去掉标签、把实体换回来 */
 private fun plain(raw: String): String =
 clean(raw.replace(Regex("<[^>]+>"), ""))

 private fun clean(s: String): String = s
 .replace("&amp;", "&")
 .replace("&lt;", "<")
 .replace("&gt;", ">")
 .replace("&quot;", "\"")
 .replace("&#39;", "'")
 .replace("&apos;", "'")
 .replace("&nbsp;", " ")
 .replace("\n", " ")
 .replace(Regex("\\s{2,}"), " ")
 .trim()
}
