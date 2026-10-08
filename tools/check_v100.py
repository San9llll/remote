#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nakour · 1.00.0 渲染重构的不变量检查

为什么要有这个：这次改的是"背景只画一遍 + 卡片实时折射 + 涟漪排队"，
牵扯 5 个文件、删了一整个函数、改了两个函数签名。这类改动**编译能过但行为错**
的情况特别多（比如漏改一个调用点、纱层画进了采样层、涟漪又变回单槽位），
而本地没有 Android SDK，编译只能等 CI，真机更是只有用户能看。

所以把"这次重构必须成立的那些事"写成可执行的检查：
先在改动前的 commit 上跑一遍确认它报红（证明它真的在管这些事），
再在改动后跑一遍确认全绿。

用法：在工程根目录  python3 tools/check_v100.py
"""
import re
import sys

SRC = "app/src/main/java/lo/naui"
F = {
    "shell": SRC + "/ui/shell/AppShell.kt",
    "rip": SRC + "/ui/theme/BgRipples.kt",
    "bg": SRC + "/ui/home/HomeSceneBackdrop.kt",
    "prefs": SRC + "/ui/theme/ThemePrefs.kt",
    "theme": SRC + "/ui/settings/ThemeScreen.kt",
    "card": SRC + "/ui/component/GlassCard.kt",
    "main": SRC + "/MainActivity.kt",
    "api": SRC + "/agent/AgentApi.kt",
    "store": SRC + "/agent/AgentStore.kt",
    "chat": SRC + "/agent/AgentChat.kt",
}


def read(k):
    with open(F[k], encoding="utf-8") as f:
        return f.read()


def strip_code(src):
    """把字符串和注释抠掉，只留真代码（不然注释里提到的名字会误报）"""
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append(" ")
            continue
        c = src[i]
        if c in '"\'':
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == c:
                    i += 1
                    break
                i += 1
            out.append(" ")
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            out.append("\n")
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def balance(src):
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    for ch in src:
        if ch in "([{":
            stack.append(ch)
        elif ch in ")]}":
            if not stack or stack[-1] != pairs[ch]:
                return False
            stack.pop()
    return not stack


def idx(hay, needle):
    """找位置，找不到回 -1（顺便记一条错，别让脚本崩在这儿）"""
    i = hay.find(needle)
    if i < 0:
        errs_global.append("找不到 %s —— 这块结构跟预期不一样" % needle)
    return i


errs_global = []


def main():
    errs, notes = [], []
    code = {k: strip_code(read(k)) for k in F}

    def need(cond, msg):
        if not cond:
            errs.append(msg)

    def forbid(cond, msg):
        if cond:
            errs.append(msg)

    # ---------- ① 背景只画一遍 ----------
    shell = code["shell"]
    n_layer = shell.count("layerBackdrop(backdrop)")
    need(n_layer == 1, "layerBackdrop 应该只挂一处（现在 %d 处）—— 挂两处就等于又画两遍全屏壁纸" % n_layer)
    forbid("AppBackdropLayer(" in shell, "老函数 AppBackdropLayer 还有调用（应该已经拆成 Images + Scrim）")
    forbid(re.search(r"blurred\s*=\s*false", shell), "还有 blurred = false 的素材层没删干净")
    need("AppBackdropImages(" in shell and "AppBackdropScrim(" in shell,
         "AppBackdropImages / AppBackdropScrim 得都在（图和纱分开画）")

    # 纱必须在采样层**外面**：Scrim 的调用位置要在 layerBackdrop 那个 Box 之后
    # ⚠️ 用 idx() 而不是 .index()：找不到就记一条错，别让整个脚本崩掉
    #（第一版在这儿抛 ValueError，"报红"变成了"崩溃"，等于什么都没检查）
    i_layer = idx(shell, "layerBackdrop(backdrop)")
    i_scrim = idx(shell, "AppBackdropScrim(")
    i_rip = idx(shell, "RippleLayer(")
    if min(i_layer, i_scrim, i_rip) >= 0:
        need(i_scrim > i_layer, "纱层必须画在 layerBackdrop 之后（画在里面的话卡片折射出来就是一团黑）")
        need(i_layer < i_rip < i_scrim,
             "涟漪必须画在采样层内部（画在外面就不会被模糊，圆里圆外两种糊法）")

    # ---------- ② 那个"涟漪期间把 backdrop 置 null"的 hack 必须没了 ----------
    forbid("ripplesActive" in shell, "ripplesActive 还在 —— 它就是「卡片玻璃永久失效」那个 bug 的根源")
    forbid("LocalGlassBackdrop" in shell,
           "AppShell 里不该再动 LocalGlassBackdrop（由 MainActivity 那层一直提供）")
    need("LocalGlassBackdrop" in code["main"], "MainActivity 得继续 provide LocalGlassBackdrop，不然卡片拿不到 backdrop")

    # ---------- ③ 涟漪是队列，不是单槽位 ----------
    rip = read("rip")
    ripc = code["rip"]
    need("val to: ImageBitmap" in rip, "Ripple 必须自带它要推出来的那张图（不然连点还是会互相顶掉）")
    need("fun fire(to: ImageBitmap)" in rip, "fire 要接收新图")
    need("MAX_QUEUE" in ripc, "队列要有上限（连点十次堆十层全屏绘制 = 卡死）")
    need("fun prune(" in ripc, "得有 prune 把跑完的摘掉 —— 摘空那一下才会通知外壳扶正背景")
    need("settledTo" in ripc, "得有 settledTo 告诉外壳最后该换成哪张")
    forbid("fun add(" in ripc, "BgRipples.add 没人用，属于死代码")
    forbid("edgeAlphaOf" in ripc, "edgeAlphaOf 没人用，属于死代码")
    need("BgRipples.fire(" in shell and "pendingWallpaper" not in shell.replace(" ", ""),
         "外壳应该 fire(图) 入队，不该再有 pendingWallpaper 这个单槽位")

    # ---------- ④ 帧驱动不许常驻空转 ----------
    i_rl = idx(shell, "private fun RippleLayer")
    body = shell[i_rl:] if i_rl >= 0 else ""
    if body:
        nxt = body.find("\nprivate ", 10)
        body = body[:nxt] if nxt > 0 else body
    forbid("LaunchedEffect(Unit)" in body, "RippleLayer 里不许再有 LaunchedEffect(Unit) 的常驻循环")
    need("LaunchedEffect(running)" in body, "帧循环要挂在 running 上，队列一空就退出")
    forbid("delay(33)" in body, "还在用固定 delay(33) 轮询（应该用 withFrameNanos 跟渲染对齐）")
    need("% 2 == 0" in body, "30fps 那刀得留着：现在涟漪每帧带着壁纸模糊+backdrop 重录一起跑")
    need("withFrameNanos" in body, "帧循环应该用 withFrameNanos 驱动")

    # ---------- ⑤ 模糊可调，三处共用一个数 ----------
    prefs = read("prefs")
    need('sp.getFloat("bg_blur"' in prefs, "ThemePrefs 里得有 bgBlur（背景模糊）")
    need("fun updateBgBlur(" in prefs, "得有 updateBgBlur")
    need("?: Liquid" in prefs, "卡片风格默认值应该是液态玻璃")
    need('sp.getFloat("glass_blur", 18f)' in prefs, "glassBlur 默认值应该跟着改成 18（0 的话液态玻璃像贴片）")
    need("prefs.bgBlur" in read("theme"), "主题页得有背景模糊那个滑块")
    need('bgBlur * 0.8f' in shell or "bgBlur * 0.8f" in shell, "内容页底图应该按 bgBlur*0.8 走（保持原来 26/32 的比例）")
    forbid("blur(32.dp" in read("bg"), "壁纸的模糊半径不该再写死 32dp")
    forbid("blur(26.dp" in shell, "内容页底图的模糊半径不该再写死 26dp")
    need("bgBlur: Dp" in read("bg"), "HomeSceneBackdrop 要接收 bgBlur 参数")

    # 圆心坐标：必须同一套坐标系
    # ⚠️ 用**去掉注释**的版本看 —— 注释里会提到老写法（screenHeightDp），
    # 拿原文查会误报。第一版就是这么误报的。
    th = strip_code(read("theme"))
    need("findRootCoordinates()" in th and "positionInRoot()" in th,
         "涟漪圆心要用根布局坐标系（positionInWindow ÷ screenHeightDp 是错的，窗口高≠屏幕高）")
    forbid("screenHeightDp" in th, "不该再拿 screenHeightDp 给窗口坐标归一化")

    # ---------- ⑥ Agent：成功路径不许 disconnect ----------
    api = read("api")
    api_code = code["api"]
    bad = [m.start() for m in re.finditer(r"conn\.disconnect\(\)", api_code)]
    # 允许出现在错误路径（runCatching/getOrElse、code !in 200..299）里
    for pos in bad:
        ctx = api_code[max(0, pos - 220):pos]
        if "getOrElse" not in ctx and "!in 200..299" not in ctx:
            errs.append("AgentApi 里有一处 disconnect 不在错误路径上（会把 keep-alive 连接掐掉，"
                        "下一轮又得重新握手）")
            break
    notes.append("AgentApi 里剩 %d 处 disconnect（都该在错误路径上）" % len(bad))

    store = read("store")
    m = re.search(r'TOOL_NOTE = """(.*?)"""', store, re.S)
    need(m is not None and len(m.group(1)) < 800,
         "系统提示词应该瘦到 800 字符以内（每次请求都发，直接决定首字延迟）")
    if m:
        notes.append("TOOL_NOTE 现在 %d 字符" % len(m.group(1)))
    need("env.sh" not in store, "依赖下载功能删了，提示词里不该再提 env.sh")

    chat = read("chat")
    need("consecutiveFail >= 3" in chat, "同一工具连着失败应该 3 次就收手（原来 4 次，白等一轮）")

    # ---------- ⑦ 删掉的东西不许有残留 ----------
    import os
    for gone in ["ui/settings/BuildDepsCard.kt", "ui/settings/DevToolsCard.kt", "term/BuildDeps.kt"]:
        need(not os.path.exists(SRC + "/" + gone), gone + " 应该已经删了")
    leftovers = os.popen(
        "grep -rln 'BuildDeps\\|DevToolsCard\\|deployToWorkspace' %s 2>/dev/null" % SRC
    ).read().strip()
    need(not leftovers, "还有文件引用已删掉的东西：" + leftovers.replace("\n", ", "))

    # ---------- ⑧ 括号平衡（所有改过的文件）----------
    for k in F:
        need(balance(code[k]), k + " 括号不平衡")

    errs.extend(errs_global)

    print("-" * 62)
    for n in notes:
        print("   · " + n)
    if errs:
        print("✗ %d 个问题：" % len(errs))
        for e in errs:
            print("   · " + e)
        return 1
    print("✅ 1.00.0 渲染重构的不变量全部成立")
    print("   （仍然**不等于能编译**，本地没 Android SDK；真机观感也得用户看）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
