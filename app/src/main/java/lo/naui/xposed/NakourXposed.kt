package lo.naui.xposed

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Nakour 的 LSPosed 模块入口。
 *
 * ## 它想干什么
 *
 * 用户机器是 ColorOS，那边的"智能侧边栏"是**系统自己的 View**，
 * 第三方应用光靠普通 API 是插不进去的（`SYSTEM_ALERT_WINDOW` 只能飘一层浮窗，
 * 做不到"成为侧边栏里的一项"）。所以要走 Xposed：把自己挂进 SystemUI 进程。
 *
 * ## 为什么现在还是"半成品"
 *
 * **hook 点必须从真机抓。** OShin 那个模块是专门针对 ColorOS 16 逆出来的，
 * 类名（侧边栏容器、菜单项 ViewGroup）每个系统版本都不一样，
 * 我在开发环境里猜出来的一定是错的。
 *
 * 所以这一步做的事是：**先把框架搭好，再让它自己把候选类名吐出来**。
 * 打开下面的 [DUMP_MODE]，重开一次侧边栏，LSPosed 日志里就会列出
 * SystemUI 里所有像侧边栏的类 —— 把那些发我，我把真正的 hook 点补上。
 *
 * ## 怎么用
 *
 * 1. LSPosed 里启用本模块，作用域勾**系统界面（com.android.systemui）**
 * 2. 重启 SystemUI（或者重启手机）
 * 3. 想看候选类名就把 [DUMP_MODE] 改成 true，重开侧边栏，去 LSPosed 日志里翻
 */
class NakourXposed : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Nakour"

        /**
         * 打开后会把 SystemUI 里"像侧边栏"的类名列进日志。
         *
         * 这是给"我还不知道 hook 哪"这个阶段用的。等你把日志发我、
         * 真 hook 点补上了，这个就可以关掉。
         */
        private const val DUMP_MODE = true

        /** 包里带这些字的类名，先收集起来看看 */
        private val INTERESTING = listOf(
            "sidebar", "SideBar", "Sidebar",
            "panel", "Panel",
            "edge", "Edge",
            "gesture", "Gesture",
            "smart", "Smart",
            "assist", "Assist",
        )

        /** 我们自己应用的包名 —— 用来跳回来 */
        private const val SELF = "lo.naui"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只在 SystemUI 里干活
        val pkg = lpparam.packageName
        if (pkg != "com.android.systemui" && pkg != "com.oplus.systemui" &&
            pkg != "com.coloros.systemui"
        ) {
            return
        }

        log("挂上了：$pkg（classLoader=${lpparam.classLoader}）")
        HookProbe.sayAlive()

        // ---- 自动适配 hook 点 ----
        // 初次装 / 系统更新之后跑一次，结果写到 sdcard/Nakour/hook_probe.json，
        // App 那边读它显示进度条。
        // 单独开线程，别把 SystemUI 的启动卡住。
        runCatching {
            Thread {
                runCatching {
                    val names = HookProbe.listClassNames(lpparam.classLoader)
                    HookProbe.adapt(names)
                }.onFailure { log("适配失败：${it.message}") }
            }.start()
        }

        if (DUMP_MODE) {
            dumpCandidates(lpparam)
        }

        hookActivityCreate(lpparam)
    }

    /* ================= 探测：把像侧边栏的类名吐出来 ================= */

    private fun dumpCandidates(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 从已知的几个"入口类"往下扒它们的字段类型，比全量扫类表便宜得多
        val seeds = listOf(
            "com.android.systemui.SystemUIApplication",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.android.systemui.statusbar.phone.StatusBar",
            "com.android.systemui.shared.system.ActivityManagerWrapper",
        )

        log("---- 开始找侧边栏候选 ----")
        seeds.forEach { name ->
            runCatching {
                val cls = XposedHelpers.findClass(name, lpparam.classLoader)
                var n = 0
                cls.declaredFields.forEach { f ->
                    val t = f.type.name
                    if (INTERESTING.any { t.contains(it) }) {
                        log("候选字段  $name.${f.name} : $t")
                        n++
                    }
                }
                cls.declaredMethods.forEach { m ->
                    val t = m.returnType.name
                    if (INTERESTING.any { t.contains(it) }) {
                        log("候选方法  $name.${m.name}() : $t")
                        n++
                    }
                }
                if (n == 0) log("  $name 里没有像侧边栏的东西")
            }.onFailure { log("  扒 $name 失败：${it.message}") }
        }
        log("---- 找完了 ----")
    }

    /* ================= 真正的挂载点（骨架） ================= */

    /**
     * hook 每个 Activity 的 onCreate。
     *
     * 为什么从这儿下手：侧边栏不管长什么样，它最终得**挂到一个窗口上**。
     * 等侧边栏那个 Activity / View 出现的时候，我们就有机会往它的
     * 菜单容器里插一项 —— 这就是"成为侧边栏里的一项"的做法。
     *
     * 现在只打日志，等你把 [DUMP_MODE] 的输出发过来，我把
     * [tryAttachToSidebar] 里补上真正的类名和插入逻辑。
     */
    private fun hookActivityCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        val name = act.javaClass.name
                        if (INTERESTING.any { name.contains(it) }) {
                            log("看起来像侧边栏的 Activity 起来了：$name")
                            tryAttachToSidebar(act)
                        }
                    }
                },
            )
            log("Activity.onCreate 挂上了")
        }.onFailure { log("hook Activity.onCreate 失败：${it.message}") }
    }

    /**
     * 把 Nakour 的入口插进侧边栏。
     *
     * ⚠️ **这里现在是空的** —— 缺的就是真机抓出来的类名。
     * 要做的事大致是：
     *
     * ```kotlin
     * // 1. 从侧边栏的布局里找到"装菜单项的那个容器"
     * val container = XposedHelpers.getObjectField(act, "mMenuContainer") as ViewGroup
     *
     * // 2. 照着已有菜单项的样子造一个，点击时跳回 Nakour
     * val item = 造一个 ItemView(act) { 跳回来() }
     * container.addView(item)
     * ```
     *
     * 第 1 步那个字段名（`mMenuContainer` / `mSidebarPanel` / …）**必须是真机抓的**。
     */
    private fun tryAttachToSidebar(act: Activity) {
        log("准备往 ${act.javaClass.name} 里插入口（还没实现，等真机类名）")
        // TODO: 拿到 hook 点之后在这儿插
    }

    /* ================= 工具 ================= */

    /** 跳回 Nakour 自己 */
    private fun openSelf(from: Activity) {
        runCatching {
            from.startActivity(
                Intent().setClassName(SELF, "$SELF.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 遍历一个 ViewGroup，找里面"像菜单项"的容器。
     *
     * 这个不依赖具体类名，是先给真机调试用的兜底：
     * 把所有 ViewGroup 的类名和子 View 数打出来，侧边栏里那个装着一排
     * 图标的，一眼就能看出来。
     */
    private fun describeTree(v: View, depth: Int = 0) {
        if (depth > 6) return
        log("  ".repeat(depth) + v.javaClass.name + "  " +
            (v.layoutParams?.let { "lp=" + it.width + "x" + it.height } ?: ""))
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) describeTree(v.getChildAt(i), depth + 1)
        }
    }

    private fun log(msg: String) {
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
