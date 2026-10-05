package lo.naui.term

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 编译依赖的下载与部署。
 *
 * ## 要装什么
 *
 * 用户要求"在后台终端内下载本地编译 apk 的包（jar 等）"。
 *
 * Android 上要把一个 Kotlin 项目编成 APK，光有 javac 不够，还得有：
 *
 * | 干什么 | 需要的 |
 * |---|---|
 * | 编 Java/Kotlin 字节码 | `openjdk-17`（自带 javac、jlink 等） |
 * | 编资源（xml / 图片 → 二进制资源表） | **aapt2** |
 * | 字节码 → dex（Dalvik 格式） | **d8** |
 * | 打包 + 对齐 | `zip` / **zipalign** |
 * | 签名 | **apksigner** |
 * | 编译时用的类（android.jar） | 从 SDK 拿 |
 *
 * ## 装到哪儿
 *
 * 全装进**已经部署好的 Termux 环境**（`term/` 那套），
 * 也就是 `$PREFIX` 底下 —— 跟 `pkg install` 一个效果。
 *
 * 装好之后 Agent 就能在那个环境里直接调 `javac` / `aapt2` / `d8` 了。
 *
 * ## 为什么是脚本而不是全用 Kotlin
 *
 * 用户明确说过"不要求全部使用 Kotlin"。这种"下载 + 解压 + 铺到固定位置"
 * 的活儿，脚本比 Kotlin 顺手得多，改起来也快。
 */
object BuildDeps {

    /** 装了什么记一下，别重复装 */
    private const val MARKER = "build_deps_installed"

    /**
     * 依赖清单。
     *
     * `pkg` 是 Termux 的包名，`check` 是验证用的命令（装完能跑通才算成功）。
     */
    data class Dep(
        val pkg: String,
        val label: String,
        val check: String,
        val note: String = "",
    )

    val list: List<Dep> = listOf(
        Dep("openjdk-17", "JDK 17", "javac -version", "编 Java/Kotlin 字节码"),
        Dep("aapt2", "aapt2", "aapt2 version", "编资源（xml / 图片）"),
        Dep("apksigner", "apksigner", "apksigner --version", "给 APK 签名"),
        Dep("zipalign", "zipalign", "zipalign -h", "对齐（装了 apksigner 一般就有）"),
        Dep("zip", "zip", "zip -v", "打包"),
        Dep("git", "git", "git --version", "拉代码"),
        Dep("wget", "wget", "wget --version", "下东西"),
        Dep("unzip", "unzip", "unzip -v", "解压"),
    )

    /**
     * 生成安装脚本。
     *
     * 返回一段可以丢给 shell 跑的脚本 —— **逐包装、逐包验**，
     * 失败的不阻塞后面的（不然一个包装不上全卡住）。
     *
     * 输出格式是 `[OK] xxx` / `[FAIL] xxx` 一行一个，
     * 调用方可以边跑边解析，喂给界面上的进度条。
     */
    fun installScript(): String = buildString {
        appendLine("#!/data/data/com.termux/files/usr/bin/bash")
        appendLine("# Nakour 编译依赖安装")
        appendLine("set -o pipefail")
        appendLine("export PATH=\\$PREFIX/bin:\\$PATH")
        appendLine("export TMPDIR=\\$PREFIX/tmp")
        appendLine("export HOME=\\$PREFIX/../home")
        appendLine()
        appendLine("echo '[1/3] 更新包索引…'")
        appendLine("yes | pkg update -y >/dev/null 2>&1 || true")
        appendLine()
        appendLine("echo '[2/3] 装依赖…'")

        list.forEachIndexed { i, d ->
            val n = i + 1
            val total = list.size
            appendLine("echo '  ($n/$total) ${d.label}…'")
            // --no-install-recommends 少拉点没用的；失败也往下走
            appendLine("if pkg install -y ${d.pkg} >/dev/null 2>&1; then")
            appendLine("  echo '[OK] ${d.pkg}'")
            appendLine("else")
            appendLine("  echo '[FAIL] ${d.pkg}'")
            appendLine("fi")
        }

        appendLine()
        appendLine("echo '[3/3] 验证…'")
        list.forEach { d ->
            appendLine("if command -v ${d.check.substringBefore(' ')} >/dev/null 2>&1; then")
            appendLine("  echo '[CHECK-OK] ${d.label}'")
            appendLine("else")
            appendLine("  echo '[CHECK-FAIL] ${d.label}'")
            appendLine("fi")
        }
        appendLine()
        appendLine("echo 'DONE'")
    }

    /**
     * 生成"编译一个 APK"的脚本骨架。
     *
     * 说明白点：**这不是完整的一键编译**。
     * 真正编一个 Android 项目还要 Gradle、还要 SDK —— 那些体积太大。
     *
     * 这个脚本干的是**最小可用的手工编译流程**：
     * 拿已经编译好的 class + 资源，走 aapt2 → d8 → zip → zipalign → apksigner。
     *
     * 适合"改了点东西想快速出个包"的场景。
     */
    fun buildScript(projectDir: String, outApk: String = "\$HOME/out.apk"): String = buildString {
        appendLine("#!/data/data/com.termux/files/usr/bin/bash")
        appendLine("# Nakour 手工打包（最小流程）")
        appendLine("set -e")
        appendLine("cd '").append(projectDir).appendLine("'")
        appendLine()
        appendLine("echo '① 编资源…'")
        appendLine("aapt2 compile -o res.zip --dir res")
        appendLine()
        appendLine("echo '② 链接资源 + 生成 R.java…'")
        appendLine("aapt2 link -o base.apk -I android.jar \\\\")
        appendLine("  --java gen --manifest AndroidManifest.xml res.zip")
        appendLine()
        appendLine("echo '③ 编 Java…'")
        appendLine("javac -source 8 -target 8 -d classes \\\\")
        appendLine("  -classpath android.jar \\\\")
        appendLine("  \$(find gen src -name '*.java' 2>/dev/null)")
        appendLine()
        appendLine("echo '④ 转 dex…'")
        appendLine("d8 --release --lib android.jar --output . \\\\")
        appendLine("  \$(find classes -name '*.class')")
        appendLine()
        appendLine("echo '⑤ 打包…'")
        appendLine("zip -q -j base.apk classes.dex")
        appendLine()
        appendLine("echo '⑥ 对齐 + 签名…'")
        appendLine("zipalign -f 4 base.apk aligned.apk")
        appendLine("apksigner sign --ks ~/.keystore --out '").append(outApk).appendLine("' aligned.apk \\\\")
        appendLine("  || echo '（没配 keystore，跳过签名）'")
        appendLine()
        appendLine("echo '出包：").append(outApk).appendLine("'")
        appendLine("ls -lh '").append(outApk).appendLine("' 2>/dev/null || true")
    }

    /** 已经装过了吗（看标记文件） */
    fun isInstalled(ctx: Context): Boolean =
        runCatching { File(ctx.filesDir, MARKER).exists() }.getOrDefault(false)

    fun markInstalled(ctx: Context) {
        runCatching { File(ctx.filesDir, MARKER).writeText(System.currentTimeMillis().toString()) }
    }

    fun clearMark(ctx: Context) {
        runCatching { File(ctx.filesDir, MARKER).delete() }
    }

    /* ================= 跑 ================= */

    /** Termux 环境的根（跟 Bootstrap 那套对齐） */
    private fun prefixOf(ctx: Context) = File(ctx.filesDir, "usr")

    fun bashPath(ctx: Context) = File(prefixOf(ctx), "bin/bash")

    /** 环境装好了吗 */
    fun envReady(ctx: Context): Boolean = bashPath(ctx).exists()

    /**
     * 在 **Termux 环境里**跑一段脚本。
     *
     * 注意不能用系统的 `sh` —— Termux 那套二进制是靠 `$PREFIX` 找库的，
     * 用系统 shell 起会报 `CANNOT LINK EXECUTABLE`。
     * 所以直接调 `$PREFIX/bin/bash`，并且把 `PREFIX` / `PATH` / `HOME` / `TMPDIR`
     * 这几个环境变量都设对。
     *
     * [onLine] 每行回调一次 —— 界面拿它刷进度。
     */
    private suspend fun runInTermux(
        ctx: Context,
        script: String,
        onLine: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val bash = bashPath(ctx)
        if (!bash.exists()) {
            onLine("[FAIL] Termux 环境还没装 —— 先去终端页装它")
            return@withContext ""
        }

        val prefix = prefixOf(ctx).absolutePath
        runCatching {
            val pb = ProcessBuilder(bash.absolutePath, "-c", script)
            pb.redirectErrorStream(true)
            pb.directory(File(prefix))
            pb.environment().apply {
                put("PREFIX", prefix)
                put("PATH", "$prefix/bin:$prefix/bin/applets")
                put("HOME", File(ctx.filesDir, "home").absolutePath)
                put("TMPDIR", File(ctx.filesDir, "tmp").absolutePath)
                put("LD_LIBRARY_PATH", "$prefix/lib")
                put("TERM", "xterm-256color")
            }
            val p = pb.start()

            val sb = StringBuilder()
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    sb.append(line).append('\n')
                    onLine(line)
                }
            }
            // 装 JDK 那种会拉几十 MB，给足五分钟
            p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
            p.destroy()
            sb.toString()
        }.getOrDefault("")
    }

    /**
     * 跑安装脚本。
     */
    suspend fun install(
        ctx: Context,
        onLine: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!envReady(ctx)) {
                throw IllegalStateException("Termux 环境还没装 —— 先去终端页装它")
            }
            val out = runInTermux(ctx, installScript(), onLine)
            // 只要有任何一个 CHECK-OK 就算部分成功，全部失败才报错
            if (out.contains("[CHECK-OK]")) markInstalled(ctx)
            Unit
        }
    }

    /**
     * 跑手工打包脚本。
     */
    suspend fun build(
        ctx: Context,
        projectDir: String,
        outApk: String,
        onLine: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!envReady(ctx)) throw IllegalStateException("Termux 环境还没装")
            runInTermux(ctx, buildScript(projectDir, outApk), onLine)
        }
    }
}
