package lo.naui.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Agent页那一套玻璃参数 +联网搜索开关。
 *
 *##为什么单独一份，不塞进 AgentStore/ThemePrefs
 *
 *用户的要求：「agent页的玻璃参数与主题参数分开，agent页内卡片在 agent设置页内改动」。
 *也就是 Agent页的卡片**不吃主题里那几根滑块**：
 *主题里调模糊 →带话：首页、设置页、终端页都跟着变，但 Agent页不动；
 *在 Agent配置页里调，只影响 Agent页的卡片和那条输入栏。
 *
 *所以它是第三份配置（`nakour_agent_glass`）：
 * ·[lo.naui.ui.theme.ThemePrefs]→全 App的卡片
 * ·[AgentStore]→服务商 /生成参数 /工具开关
 * ·这里 →Agent页自己的观感 +联网搜索
 *
 *默认值跟主题那份对齐（模糊18、折射20），这样刚升级上来的人
 *看到的 Agent页跟以前差不多，不会觉得"怎么突然变了"。
 */
object AgentGlassPrefs {

 private var sp: SharedPreferences? = null

 /**开没开真折射。关掉就退回纯2D叠层（出问题时的退路） */
 var liquid by mutableStateOf(true)
 private set

 /**模糊半径，dp */
 var blur by mutableStateOf(18f)
 private set

 /**折射深度，dp */
 var lens by mutableStateOf(20f)
 private set

 /**面板色的不透明度 */
 var alpha by mutableStateOf(0.26f)
 private set

 /**卡片 /输入栏的圆角，dp */
 var radius by mutableStateOf(20)
 private set

 /**
 *联网搜索总开关。
 *
 ***关着的时候 web_search这个工具根本不发给模型** ——
 *不是发过去再拒绝。少一条工具定义，提示词也短一截，
 *模型不会惦记着去搜一件它做不到的事。
 *
 *默认关：这是要往外发网络请求的动作，得用户自己点开。
 */
 var webSearch by mutableStateOf(false)
 private set

 /**用哪家：bing / baidu */
 var searchEngine by mutableStateOf(WebSearch.ENGINE_BING)
 private set

 fun init(ctx: Context) {
 if (sp != null) return
 val p = ctx.getSharedPreferences("nakour_agent_glass", Context.MODE_PRIVATE)
 sp = p
 liquid = p.getBoolean("liquid", true)
 blur = p.getFloat("blur",18f)
 lens = p.getFloat("lens",20f)
 alpha = p.getFloat("alpha",0.26f)
 radius = p.getInt("radius",20)
 webSearch = p.getBoolean("web_search", false)
 searchEngine = p.getString("search_engine", WebSearch.ENGINE_BING)
 ?.takeIf { it == WebSearch.ENGINE_BING || it == WebSearch.ENGINE_BAIDU }
 ?: WebSearch.ENGINE_BING
 }

 fun updateLiquid(v: Boolean) { liquid = v; sp?.edit()?.putBoolean("liquid", v)?.apply() }

 fun updateBlur(v: Float) {
 blur = v.coerceIn(0f,40f)
 sp?.edit()?.putFloat("blur", blur)?.apply()
 }

 fun updateLens(v: Float) {
 lens = v.coerceIn(0f,40f)
 sp?.edit()?.putFloat("lens", lens)?.apply()
 }

 fun updateAlpha(v: Float) {
 alpha = v.coerceIn(0.05f,0.95f)
 sp?.edit()?.putFloat("alpha", alpha)?.apply()
 }

 fun updateRadius(v: Int) {
 radius = v.coerceIn(0,36)
 sp?.edit()?.putInt("radius", radius)?.apply()
 }

 fun updateWebSearch(v: Boolean) {
 webSearch = v
 sp?.edit()?.putBoolean("web_search", v)?.apply()
 }

 fun updateSearchEngine(v: String) {
 searchEngine = if (v == WebSearch.ENGINE_BAIDU) WebSearch.ENGINE_BAIDU else WebSearch.ENGINE_BING
 sp?.edit()?.putString("search_engine", searchEngine)?.apply()
 }
}
