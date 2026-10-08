package lo.naui.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一个能交给 AI 调的工具 */
data class AgentTool(
    val name: String,
    val description: String,
    /** JSON Schema 的 properties */
    val properties: JSONObject,
    val required: List<String>,
) {
    /** OpenAI function calling 的写法 */
    fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required))
                )
        )
}

private fun str(desc: String): JSONObject =
    JSONObject().put("type", "string").put("description", desc)

private fun int(desc: String): JSONObject =
    JSONObject().put("type", "integer").put("description", desc)

private fun boolean(desc: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", desc)

private fun arr(desc: String): JSONObject =
    JSONObject()
        .put("type", "array")
        .put("description", desc)
        .put("items", JSONObject().put("type", "string"))

/**
 * action 那种"一个工具几个动作"的写法。
 *
 * 为什么把 4 个文件操作塞进一个工具而不是开 4 个：
 * 工具 schema 每次请求都要发一遍，20 个工具和 12 个工具差着几百 token 的
 * prefill —— 而模型对 enum 参数的把握不比独立工具差（batch_shell 已经验证过）。
 */
private fun act(desc: String, vararg options: String): JSONObject =
    JSONObject()
        .put("type", "string")
        .put("description", desc)
        .put("enum", org.json.JSONArray(options as Array<String>))

/**
 * 内嵌工具链。
 *
 * 给 AI 这几个"手"：跑命令、读写文件、列目录。
 * 跑在哪个环境由用户在输入栏那个弹窗里选（本机 root / 沙箱）。
 *
 * 工具执行前**不需要用户确认** —— 用户明确要"让 ai 真正的能够操作手机底层"。
 * 但沙箱模式有路径围栏，本机模式才放得开。
 */
object AgentTools {

    const val SHELL = "run_shell"
    const val BATCH = "batch_shell"
    const val READ_MANY = "read_many"
    const val FIND = "find_files"
    const val GREP = "grep_text"
    const val READ = "read_file"
    const val WRITE = "write_file"
    const val LIST = "list_dir"
    const val DEVICE = "device_info"
    const val DOWNLOAD = "start_download"
    const val DOWNLOAD_CHECK = "check_download"

    // 1.02.0 新增：把"动文件 / 动设备"从 shell 里捞出来，走围栏、走结构化确认
    const val FILE_OP = "file_op"
    const val DELETE = "delete_path"
    const val TREE = "tree"
    const val STAT = "stat"
    const val ARCHIVE = "archive"
    const val APPS = "list_apps"
    const val OPEN = "open_app"
    const val CLIPBOARD = "clipboard"
    const val NOTICE = "notify"
    const val SHOT = "screenshot"

    val ALL: List<AgentTool> = listOf(
        AgentTool(
            name = SHELL,
            description = "在设备上执行一条 shell 命令，返回它的输出。" +
                "可以看文件、跑命令、装东西、改配置。沙箱环境下只能在 app 的 data 目录里活动。\n\n" +
                "**注意**：默认都是普通用户权限。如果这条命令**必须**用 root（要写系统分区、" +
                "动别的 app 的数据、改系统设置），那要先把 `reason` 填上 —— " +
                "用户会看到你想干什么、以及你为什么要这么干，他同意了你才能拿到 su。",
            properties = JSONObject()
                .put("command", str("要执行的命令，比如 ls -al /sdcard 或者 pm list packages"))
                .put("reason", str(
                    "如果这条命令需要 root 权限，在这儿说清楚**为什么**。" +
                        "比如「要改 /system 下的某个配置来关掉这个功能，因为设置界面里没有开关」。" +
                        "不需要 root 的命令就不用填。"
                ))
                .put("need_root", JSONObject()
                    .put("type", "boolean")
                    .put("description", "这条命令是不是必须用 root 跑，默认 false")),
            required = listOf("command"),
        ),
        AgentTool(
            name = READ,
            description = "读一个文本文件的内容",
            properties = JSONObject()
                .put("path", str("文件路径"))
                .put("max_bytes", int("最多读多少字节，默认 200000")),
            required = listOf("path"),
        ),
        AgentTool(
            name = WRITE,
            description = "把内容写进一个文件（会覆盖），父目录不存在会自动建",
            properties = JSONObject()
                .put("path", str("文件路径"))
                .put("content", str("要写入的文本内容")),
            required = listOf("path", "content"),
        ),
        AgentTool(
            name = LIST,
            description = "列出一个目录里有什么",
            properties = JSONObject().put("path", str("目录路径")),
            required = listOf("path"),
        ),
        AgentTool(
            name = BATCH,
            description = "一次跑多条命令（串行执行，按顺序）。" +
                "**需要连着做几步的时候优先用它** —— 比如 `ls` 看完再 `cat` 再 `grep`，" +
                "分包发的话每一条都要跟模型来回一次，很慢。一次提交能省很多轮。",
            properties = JSONObject()
                .put("commands", JSONObject()
                    .put("type", "array")
                    .put("description", "要依次执行的命令列表")
                    .put("items", JSONObject().put("type", "string")))
                .put("stop_on_error", JSONObject()
                    .put("type", "boolean")
                    .put("description", "遇到失败就停，默认 false 继续跑")),
            required = listOf("commands"),
        ),
        AgentTool(
            name = READ_MANY,
            description = "一次读多个文件的内容。想知道好几个文件里都是什么时用它，" +
                "比一个个 read_file 快得多。最多 20 个。",
            properties = JSONObject()
                .put("paths", JSONObject()
                    .put("type", "array")
                    .put("description", "要读的文件路径列表")
                    .put("items", JSONObject().put("type", "string"))),
            required = listOf("paths"),
        ),
        AgentTool(
            name = FIND,
            description = "按**文件名**找文件。比如找所有 .log、所有 build.gradle.kts。" +
                "不知道东西在哪的时候先用它，别一层层 ls 翻。\n" +
                "1.02.0 起这条**不再靠 shell 里的 find**（没装 Termux 就得赌系统有没有 toybox），" +
                "改成自己遍历 + 走路径围栏；找不到时会回\"扫了多少条目、几个目录读不了\"，" +
                "那比干巴巴一句\"没找到\"有用。",
            properties = JSONObject()
                .put("root", str("从哪儿开始找，默认当前目录"))
                .put("name", str("文件名匹配，支持通配符，比如 *.kt、config.*（不是正则）"))
                .put("max_depth", int("最多往下找几层，默认 5"))
                .put("limit", int("最多回多少个，默认 100（到数会明说截断了）")),
            required = listOf("name"),
        ),
        AgentTool(
            name = GREP,
            description = "在**文件内容**里搜关键词，返回 文件:行号: 内容。" +
                "找「这东西在哪定义、在哪用到」就靠它，比读一堆文件快。\n" +
                "1.02.0 起不再依赖 shell 的 grep，自己遍历 + 走围栏；" +
                "带 context 参数可以一次把上下文行也拿到，省掉\"搜到再去 read_file\"那一轮。",
            properties = JSONObject()
                .put("root", str("从哪个目录找，默认当前目录"))
                .put("pattern", str("要找的内容（普通字符串包含匹配，不是正则）"))
                .put("file_glob", str("只搜哪些文件，默认 * ，比如 *.kt"))
                .put("context", int("每个命中带上下几行，默认 0（最多 4）"))
                .put("ignore_case", boolean("忽略大小写，默认 false"))
                .put("limit", int("最多回几个命中，默认 60（到数会明说截断了）")),
            required = listOf("pattern"),
        ),
        AgentTool(
            name = DEVICE,
            description = "问这台设备的基本情况：型号、安卓版本、当前权限、屏幕、电量",
            properties = JSONObject(),
            required = emptyList(),
        ),
        AgentTool(
            name = DOWNLOAD,
            description = "**后台下载一个文件。立刻返回一个 id，不等它下完。**\n\n" +
                "要下东西**优先用这个**，别用 run_shell 跑 curl / wget —— " +
                "命令执行是有超时的，大文件必然被掐断，而模型看不出是被掐了就会一直重试，" +
                "这台机器上真出过「51 次工具、30 次思考还没下好」。\n\n" +
                "调完这条就接着干别的。想知道下没下好、下到百分之几，" +
                "再发一条 check_download 问一句。",
            properties = JSONObject()
                .put("url", str("完整下载地址，得是 http:// 或 https:// 开头"))
                .put("path", str(
                    "存到哪儿。相对路径按沙箱根算（比如 downloads/app.zip）；" +
                        "也可以给绝对路径（比如 /sdcard/Download/app.zip）。" +
                        "**留空**就自动用地址里的文件名存到工作区（没设工作区就存进沙箱的 downloads/）。"
                )),
            required = listOf("url"),
        ),
        AgentTool(
            name = DOWNLOAD_CHECK,
            description = "问后台那些下载到哪儿了：下到百分之几、速度、完没完、失败原因。" +
                "带 id 问一个，不带 id 就列出最近所有下载任务。\n\n" +
                "**别空转反复问** —— 问一次心里有数就够了，" +
                "它没下完之前先把别的活儿干完，或者直接跟用户说「在后台下着，稍后再看」。",
            properties = JSONObject()
                .put("id", str("start_download 返回的那个 id。留空就列最近的全部任务")),
            required = emptyList(),
        ),
        AgentTool(
            name = FILE_OP,
            description = "文件/目录操作：**复制、移动、改名、建目录**。走路径围栏，" +
                "沙箱模式下外面一律拒 —— **别再用 run_shell 拼 cp/mv/mkdir 了**，" +
                "那样子命令不过围栏，也不算真在沙箱里。\n" +
                "改名就是 move，src 和 dst 同一个目录、换个名字。\n" +
                "删除是另一个工具 delete_path（要用户点同意），别在这儿删。",
            properties = JSONObject()
                .put("action", act("要干什么", "copy", "move", "mkdir"))
                .put("src", str("源路径（mkdir 时这儿就是要建的目录路径）"))
                .put("dst", str("目标路径（copy/move 才要，mkdir 不用填）")),
            required = listOf("action", "src"),
        ),
        AgentTool(
            name = DELETE,
            description = "删除文件或目录。**这条一定会弹给用户确认**，你照常调用就行，" +
                "不用自己劝自己「为了安全我不删」。\n" +
                "删目录必须显式给 recursive=true，否则只让删单个文件 —— " +
                "这是故意的：整棵子树没了是不可逆的。\n" +
                "路径写错就是一片文件没了，**动手前先用 tree 或 stat 确认一遍**。",
            properties = JSONObject()
                .put("path", str("要删的路径"))
                .put("recursive", boolean("目录要不要整个删（默认 false，只删单文件）"))
                .put("reason", str("为什么要删 —— 会显示在确认弹窗上给用户看")),
            required = listOf("path"),
        ),
        AgentTool(
            name = TREE,
            description = "一次看一棵**目录树**（带层级、大小）。\n" +
                "比一层层 list_dir 快得多 —— 想知道「这底下到底有什么」先用它，" +
                "别 cat/ls 来回问五六轮。深度默认 3 层、节点数封顶，" +
                "要看更深把 depth 调大或者从某个子目录再问。",
            properties = JSONObject()
                .put("path", str("从哪个目录开始，默认当前目录"))
                .put("depth", int("往下几层，默认 3（最多 6）"))
                .put("max_nodes", int("最多列多少个条目，默认 200（最多 3000）")),
            required = emptyList(),
        ),
        AgentTool(
            name = STAT,
            description = "看一个东西的**元信息**：类型、大小、改动时间、读写权限；" +
                "是图片会带尺寸格式，是 APK 会带包名/版本/minSdk/权限清单，是文本会带行数。\n" +
                "「这个 apk 是什么包」「这图多大」「这文件能不能写」都用它，一次搞定。",
            properties = JSONObject().put("path", str("文件路径")),
            required = listOf("path"),
        ),
        AgentTool(
            name = ARCHIVE,
            description = "压缩包：list 看里面有什么（不解压）、unpack 解压、pack 打包成 zip。\n" +
                "支持 zip / jar / apk / tar / tar.gz / gz / **7z**（1.02.0 加的依赖）。\n" +
                "⚠️ 7z 里的加密条目解不开时会照实回「需要密码」，那是包的事，不是你命令写错。\n" +
                "解压带 zip-slip 防护：包里想写到目标目录外面的条目会被丢掉并计数。",
            properties = JSONObject()
                .put("action", act("要干什么", "list", "unpack", "pack"))
                .put("path", str("压缩包路径（pack 时这是**输出**的 zip 路径）"))
                .put("out_dir", str("解压到哪儿（unpack 才要，默认解到压缩包同级的同名目录）"))
                .put("inputs", arr("要打包进去的路径列表（pack 才要）")),
            required = listOf("action", "path"),
        ),
        AgentTool(
            name = APPS,
            description = "列这台机器上**能启动的 app**（显示名 → 包名），可按关键词过滤。\n" +
                "想打开某个 app 但不知道包名时先问它，别猜包名。",
            properties = JSONObject().put("keyword", str("过滤关键词（应用名或包名片段），留空列全部")),
            required = emptyList(),
        ),
        AgentTool(
            name = OPEN,
            description = "打开一个东西：包名、http(s) 链接、文件绝对路径、或者应用显示名" +
                "（不是包名时会按名字模糊找）。\n" +
                "「帮我装这个 apk」「打开这张图」「跳到这个网址」都用它，" +
                "文件会自动过 FileProvider 共享（直接发 file:// 路径系统会拦）。",
            properties = JSONObject().put("target", str("包名 / 链接 / 文件路径 / 应用名")),
            required = listOf("target"),
        ),
        AgentTool(
            name = CLIPBOARD,
            description = "读写系统剪贴板。\n" +
                "⚠️ 读有个系统限制：Android 10 起只有**本 app 在前台**才读得到内容，" +
                "后台读会拿到空 —— 报「剪贴板是空的」的时候不一定是真空。写不受这个限制。",
            properties = JSONObject()
                .put("action", act("要干什么", "read", "write"))
                .put("text", str("write 时要写的内容"))
                .put("label", str("内容标签（可选，系统显示用）")),
            required = listOf("action"),
        ),
        AgentTool(
            name = NOTICE,
            description = "发一条系统通知到通知栏。长任务跑完了、或者用户在别的 app 里" +
                "你得提醒他一下，用它 —— 别只在对话里写一句「好了」，那会儿用户不在界面上。\n" +
                "important=true 会有声音/横幅，用之前想想真有必要吗。",
            properties = JSONObject()
                .put("title", str("通知标题"))
                .put("text", str("通知内容"))
                .put("important", boolean("要不要更重要的提示（默认 false）")),
            required = listOf("title", "text"),
        ),
        AgentTool(
            name = SHOT,
            description = "截屏存成 PNG。\n" +
                "⚠️ 需要 root 或 Shizuku —— 普通权限下系统的 screencap 会被挡，" +
                "这条会直接告诉你「需要特权」，那种情况**别换写法重试**，" +
                "正当路子是让用户开 Shizuku，或者干脆说做不到。",
            properties = JSONObject().put("path", str("存到哪儿，默认沙箱里 screen.png，必须是 .png")),
            required = emptyList(),
        ),
    )

    /**
     * 这个环境允许用哪些工具。
     *
     * 沙箱只去掉 device_info（那上面写着"当前权限/沙箱根"，对沙箱模式没意义）。
     * 截屏/剪贴板这些**不给沙箱裁掉** —— 它们内部自己会照权限现实说话
     * （比如截屏没 root 就直说做不到），比让模型猜要省事。
     */
    fun toolsFor(env: AgentEnv): List<AgentTool> = when (env) {
        AgentEnv.Sandbox -> ALL.filter { it.name != DEVICE }
        AgentEnv.Host -> ALL
    }

    /* ================= 执行 ================= */

    /**
     * 要不要先问用户，不在这儿决定 —— 命中危险就**带回一个待批准决策**，
     * 由 AgentChat 那唯一的确认通道去问。
     *
     * （以前这儿直接调 `askUser`：弹窗逻辑散在 AgentTaskService、AgentChat 两处，
     * root 那条还另走一套字符串协议 —— 同一个"问用户"三种写法，改一处忘一处。）
     */
    suspend fun run(
        ctx: Context,
        env: AgentEnv,
        name: String,
        args: JSONObject,
        /** 命令的实时输出行（下载进度靠它） */
        onLine: ((String) -> Unit)? = null,
        /** 哪个会话在跑 —— 后台下载要往这一份的进度上报，不能串台 */
        conversationId: String = "",
    ): ToolResult {
        // 先过危险闸门。
        // 下载这条路是自己写文件、不走 shell 的，所以判危险得按**围栏解析之后**的
        // 真实落点来 —— 不然「相对路径」看着人畜无害，解出来可能在系统目录里。
        val target = if (name == DOWNLOAD) downloadTarget(ctx, env, args) else ""
        DangerGuard.risk(name, args, env, target)?.let { hit ->
            when (AgentStore.dangerPolicy) {
                DangerGuard.Policy.Deny -> return ToolResult(
                    false,
                    "这个动作被安全策略挡下来了（" + hit.category.label + "）。" +
                        "如果确实要做，让用户在 Agent 配置里把「危险操作」改成每次都问或者放行。",
                )
                DangerGuard.Policy.Allow -> Unit
                DangerGuard.Policy.Ask -> {
                    // 不在后台线程弹窗（弹不了，坑 #39），把要问的东西带上去
                    return ToolResult(
                        ok = false,
                        output = "这个动作要先经用户同意（" + hit.category.label + "），" +
                            "同意后会自动执行，不用你再发一遍。",
                        decision = RiskDecision(
                            // "改到 sdcard 之外"单独一类，标题说得清它在担心什么
                            kind = if (hit.category.id == "outside_user_area")
                                RiskKind.WriteOutside else RiskKind.Danger,
                            detail = hit.matched,
                            note = hit.category.label,
                            consequence = hit.category.consequence,
                            run = { runUnchecked(ctx, env, name, args, onLine, conversationId) },
                        ),
                    )
                }
            }
        }
        return runUnchecked(ctx, env, name, args, onLine, conversationId)
    }

    private suspend fun runUnchecked(
        ctx: Context,
        env: AgentEnv,
        name: String,
        args: JSONObject,
        onLine: ((String) -> Unit)? = null,
        conversationId: String = "",
    ): ToolResult = when (name) {
        SHELL -> AgentRunner.shell(
            ctx, env,
            args.optString("command", "").trim(),
            onLine,
            // 要 root 的话把理由一起带上 —— 界面弹窗要用
            needRoot = args.optBoolean("need_root", false),
            reason = args.optString("reason", "").trim(),
        )

        BATCH -> {
            val arr = args.optJSONArray("commands")
            val list = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { list += it }
            AgentRunner.batchShell(ctx, env, list, args.optBoolean("stop_on_error", false))
        }

        READ_MANY -> {
            val arr = args.optJSONArray("paths")
            val list = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { list += it }
            AgentRunner.readMany(ctx, env, list)
        }

        // 这两条从"拼 shell 命令"换成 AgentFsOps 的纯 Kotlin 实现：
        // 沙箱里没装 Termux 也能用，而且**过路径围栏**（shell 那条不过）
        FIND -> AgentFsOps.find(
            ctx, env,
            args.optString("root", ".").trim(),
            args.optString("name", "*").trim(),
            args.optInt("max_depth", 5),
            args.optInt("limit", 100),
        )

        GREP -> AgentFsOps.grep(
            ctx, env,
            args.optString("root", ".").trim(),
            args.optString("pattern", "").trim(),
            args.optString("file_glob", "*").trim(),
            args.optInt("context", 0),
            args.optBoolean("ignore_case", false),
            args.optInt("limit", 60),
        )

        READ -> AgentRunner.readFile(
            ctx, env,
            args.optString("path", "").trim(),
            args.optInt("max_bytes", 200_000).coerceIn(1_000, 800_000),
        )

        WRITE -> AgentRunner.writeFile(
            ctx, env,
            args.optString("path", "").trim(),
            args.optString("content", ""),
        )

        LIST -> AgentRunner.listDir(ctx, env, args.optString("path", ".").trim())

        DEVICE -> deviceInfo(ctx, env)

        DOWNLOAD -> startDownload(ctx, env, conversationId, args)

        DOWNLOAD_CHECK -> checkDownload(args)

        // ---- 1.02.0：文件与设备动作，全部走围栏 ----
        FILE_OP -> fileOp(ctx, env, args)

        DELETE -> AgentFsOps.delete(
            ctx, env,
            args.optString("path", "").trim(),
            args.optBoolean("recursive", false),
        )

        TREE -> AgentFsOps.tree(
            ctx, env,
            args.optString("path", ".").trim(),
            args.optInt("depth", 3),
            args.optInt("max_nodes", 200),
        )

        STAT -> AgentFsOps.stat(ctx, env, args.optString("path", "").trim())

        ARCHIVE -> archive(ctx, env, args)

        APPS -> AgentDeviceOps.listApps(ctx, args.optString("keyword", "").trim())

        OPEN -> AgentDeviceOps.open(ctx, args.optString("target", "").trim())

        CLIPBOARD -> when (args.optString("action", "read").trim().lowercase()) {
            "write" -> AgentDeviceOps.clipboardWrite(
                ctx,
                args.optString("text", ""),
                args.optString("label", "").trim(),
            )
            "read" -> AgentDeviceOps.clipboardRead(ctx)
            else -> ToolResult(false, "action 只认 read / write，给的是：" + args.optString("action", ""))
        }

        NOTICE -> AgentDeviceOps.notify(
            ctx,
            args.optString("title", "").trim(),
            args.optString("text", "").trim(),
            args.optBoolean("important", false),
            // 点通知回到 Agent 页
            openAgent = true,
        )

        SHOT -> AgentDeviceOps.screenshot(ctx, env, args.optString("path", "screen.png").trim())

        else -> ToolResult(false, "没有这个工具：" + name)
    }

    /* ================= 文件 / 压缩包分发 ================= */

    /**
     * file_op 的分发。
     *
     * 三个动作共用一条工具（省 prompt），但**删除不在这儿** ——
     * 删除要单独走确认，混进来会让人以为 copy/move 也要弹窗。
     */
    private suspend fun fileOp(ctx: Context, env: AgentEnv, args: JSONObject): ToolResult {
        val action = args.optString("action", "").trim().lowercase()
        val src = args.optString("src", "").trim()
        val dst = args.optString("dst", "").trim()
        if (action == "mkdir") return AgentFsOps.mkdir(ctx, env, src)
        if (src.isBlank()) return ToolResult(false, "src 是空的。")
        return when (action) {
            "copy" -> {
                if (dst.isBlank()) ToolResult(false, "copy 要 dst（目标路径）。")
                else AgentFsOps.copyOrMove(ctx, env, src, dst, copy = true)
            }
            "move" -> {
                if (dst.isBlank()) ToolResult(false, "move 要 dst（目标路径）。改名也一样，dst 填新名字的全路径。")
                else AgentFsOps.copyOrMove(ctx, env, src, dst, copy = false)
            }
            "delete" -> ToolResult(
                false,
                "删除请用 delete_path 工具（它会先让用户确认）。file_op 不做删除。",
            )
            else -> ToolResult(false, "action 只认 copy / move / mkdir，给的是：「$action」")
        }
    }

    /** archive 的分发：三个动作都要的字段不一样，分开校验 */
    private suspend fun archive(ctx: Context, env: AgentEnv, args: JSONObject): ToolResult {
        val action = args.optString("action", "").trim().lowercase()
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return ToolResult(false, "path 是空的。")
        return when (action) {
            "list" -> AgentFsOps.archiveList(ctx, env, path)
            "unpack" -> AgentFsOps.extract(
                ctx, env, path,
                args.optString("out_dir", "").trim().ifBlank {
                    // 默认解到压缩包旁边一个同名目录：app.zip → app/
                    val n = path.substringAfterLast('/')
                    path.substringBeforeLast('/') + "/" + n.substringBeforeLast('.')
                },
            )
            "pack" -> {
                val a = args.optJSONArray("inputs")
                val list = ArrayList<String>()
                if (a != null) for (i in 0 until a.length()) {
                    a.optString(i).takeIf { it.isNotBlank() }?.let { list += it }
                }
                AgentFsOps.zip(ctx, env, path, list)
            }
            else -> ToolResult(false, "action 只认 list / unpack / pack，给的是：「$action」")
        }
    }

    /* ================= 后台下载 ================= */

    /**
     * 起一个后台下载，**立刻返回**。
     *
     * 这里刻意不做任何"等一会儿"的事：下载全部在 Downloader 自己的
     * 协程里跑，AI 这条工具调用几十毫秒就拿到 id 走人。
     */
    private fun startDownload(
        ctx: Context,
        env: AgentEnv,
        conversationId: String,
        args: JSONObject,
    ): ToolResult {
        val url = args.optString("url", "").trim()
        if (url.isBlank()) return ToolResult(false, "url 是空的，给个完整下载地址。")
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return ToolResult(false, "url 得是 http:// 或 https:// 开头的完整地址，你给的是：" + url)
        }

        val target = downloadTarget(ctx, env, args)
        if (target.isBlank()) {
            val name = fileNameOf(url)
            return ToolResult(
                false,
                "这个落点在沙箱外面，下不了：" + args.optString("path", "").trim() +
                    "。要么换成相对沙箱根的路径（比如 downloads/" + name + "），" +
                    "要么让用户在输入栏那个弹窗里切到「本机（root）」环境。",
            )
        }

        val t = Downloader.start(ctx, conversationId, url, target)
        return ToolResult(
            true,
            "已经在后台下起来了，这条不用等。\n" +
                "id：" + t.id + "\n" +
                "存到：" + target + "\n\n" +
                "要进度就发一条 check_download(id=\"" + t.id + "\")。" +
                "注意别连着追问 —— 先干别的，或者跟用户说一句「在后台下着」。",
        )
    }

    /**
     * 查后台下载到哪一步了。
     *
     * ⚠️ 这里**永远回 ok=true** —— "还在下"不是失败。
     * 要是把没下完当成报错递回去，上面那个「同一工具连着失败 4 次就收手」的
     * 计数器会把老老实实查进度的模型给掐了。
     */
    private fun checkDownload(args: JSONObject): ToolResult {
        val id = args.optString("id", "").trim()
        if (id.isNotBlank()) {
            val t = Downloader.get(id)
                ?: return ToolResult(
                    true,
                    "没有 id 为 " + id + " 的下载任务（可能早就清了）。" +
                        "不带 id 再问一次，能列出最近所有的任务。",
                )
            return ToolResult(true, t.summary())
        }
        val list = Downloader.recent()
        if (list.isEmpty()) return ToolResult(true, "现在没有任何后台下载任务。")
        val sb = StringBuilder()
        sb.append("最近 ").append(list.size).append(" 个下载任务：")
        list.forEach { t -> sb.append("\n\n").append(t.summary()) }
        return ToolResult(true, sb.toString())
    }

    /**
     * 这次下载**实际**落到哪儿（已经过围栏）。
     *
     * 解不出来（沙箱模式下要往外写）就回空串 —— 上层要么拒、要么按危险路径弹窗。
     * 危险闸门和真正执行都要用它，所以单独抽出来，两处算的是同一套规则。
     */
    private fun downloadTarget(ctx: Context, env: AgentEnv, args: JSONObject): String {
        val wanted = args.optString("path", "").trim()
        val raw = if (wanted.isNotBlank()) wanted else defaultDownloadTarget(args.optString("url", ""))
        return AgentRunner.guardPath(ctx, env, raw) ?: ""
    }

    /**
     * 没给路径的时候存到哪儿。
     *
     * 优先用户设的工作区 —— 那是他明确划给 Agent 的地盘；
     * 没设就落回沙箱里的 downloads/（相对路径，围栏解得开）。
     */
    private fun defaultDownloadTarget(url: String): String {
        val name = fileNameOf(url)
        val ws = AgentStore.workspace.trim().removeSuffix("/")
        return if (ws.isNotBlank()) ws + "/" + name else "downloads/" + name
    }

    /** 从地址里认个文件名出来，认不出来就按时间戳编一个 */
    private fun fileNameOf(url: String): String {
        val seg = url.substringBefore('#').substringBefore('?').substringAfterLast('/', "")
        val decoded = runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
        val name = decoded.replace('/', '_').replace('\\', '_').trim()
        if (name.isBlank() || name == "." || name == "..") {
            return "download-" + System.currentTimeMillis()
        }
        return if (name.length > 120) name.take(120) else name
    }

    private suspend fun deviceInfo(ctx: Context, env: AgentEnv): ToolResult {
        val level = lo.naui.sys.Privilege.level(ctx)
        val sb = StringBuilder()
        sb.append("型号：").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append('\n')
        sb.append("安卓：").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append("架构：").append(android.os.Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        sb.append("当前权限：").append(level.label).append('\n')
        sb.append("执行环境：").append(env.label).append('\n')
        sb.append("Termux 环境：")
            .append(if (lo.naui.term.Bootstrap.isInstalled(ctx)) "装了" else "没装").append('\n')
        sb.append("沙箱根目录：").append(AgentRunner.sandboxRoot(ctx).absolutePath).append('\n')
        sb.append("可用空间：")
            .append(ctx.filesDir.usableSpace / 1024 / 1024).append(" MB")
        return ToolResult(true, sb.toString())
    }
}
