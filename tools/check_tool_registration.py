#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nakour · 工具链注册完整性检查（针对 start_download / check_download）

为什么要有这个检查：项目里没有可跑的单元测试，本地也没有 Android SDK，
唯一能自动化验证的就是"跨文件一致性"。而这次改动恰好是**最容易漏一处**的那种：
一个新工具要在 4 个地方同时登记（工具定义 / 执行分支 / 界面人话名 / 进度提示），
漏哪个都不会编译报错，只会在实机上表现为"显示英文工具名"或者
"没有这个工具：start_download"。

先确认它抓得住问题（在改动前的树上跑，应该报红），再确认改完是绿的。
"""
import re
import sys

AG = "app/src/main/java/lo/naui/agent"
FILES = {
    "tool": AG + "/AgentTool.kt",
    "chat": AG + "/AgentChat.kt",
    "guard": AG + "/DangerGuard.kt",
    "env": AG + "/AgentEnv.kt",
    "store": AG + "/AgentStore.kt",
}


def read(k):
    with open(FILES[k], encoding="utf-8") as f:
        return f.read()


def _scan_balance(src):
    """
    逐字符扫，跳过 // 注释、/* */ 注释、"..."、\"\"\"...\"\"\"、'.'。
    第一版是拿正则糊的，结果 AgentStore 里那段三引号提示词、
    AgentTool 里带括号的中文字符串全被算进括号数，报了 3 条假错 ——
    所以换成扫描器。
    """
    depth = {'(': 0, '{': 0, '[': 0}
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = (j + 3) if j >= 0 else n
            continue
        c = src[i]
        if c in '"\'':
            q = c
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == q:
                    i += 1
                    break
                i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in '([{':
            stack.append(c)
        elif c in ')]}':
            if not stack or stack[-1] != pairs[c]:
                line = src.count('\n', 0, i) + 1
                return False, "第 %d 行括号对不上：多了个 %s" % (line, c)
            stack.pop()
        i += 1
    if stack:
        return False, "有括号没闭合：" + "".join(stack)
    return True, ""


def braces_ok(src, name):
    """粗略括号平衡：去掉字符串和注释再数"""
    return _scan_balance(src)


def main():
    tool = read("tool")
    chat = read("chat")
    guard = read("guard")
    env = read("env")
    store = read("store")

    errs = []
    oks = []

    # ---- 0. 括号平衡 ----
    for k in FILES:
        ok, msg = braces_ok(read(k), k)
        if not ok:
            errs.append(msg)

    # ---- 1. 从 const val 里把工具名捞出来 ----
    consts = dict(re.findall(r'const val (\w+) = "([a-z_]+)"', tool))
    if not consts:
        errs.append("AgentTool.kt 里没抓到任何 const val 工具名，检查脚本本身跑歪了")
    print("抓到 %d 个工具常量：%s" % (len(consts), ", ".join(sorted(consts))))

    # ---- 2. 每个工具都要有执行分支（runUnchecked 的 when）----
    body = tool.split("private suspend fun runUnchecked", 1)
    if len(body) < 2:
        errs.append("找不到 runUnchecked，脚本本身要改")
        dispatch = ""
    else:
        dispatch = body[1].split("private ", 1)[0]
    for cname in consts:
        if not re.search(r"^\s*" + cname + r" ->", dispatch, flags=re.M):
            errs.append("执行分支缺 %s -> AI 调它会得到「没有这个工具：%s」"
                        % (cname, consts[cname]))

    # ---- 3. 界面人话名（friendlyName）----
    fn = chat.split("private fun friendlyName", 1)[-1]
    gn = chat.split("private fun guessHint", 1)[-1]
    for cname in consts:
        if cname == "SHELL":
            continue  # SHELL 在 friendlyName/guessHint 里走的是别的写法
        if "AgentTools." + cname not in fn:
            errs.append("friendlyName 缺 %s -> 界面会直接显示英文原名 %s" % (cname, consts[cname]))
        if "AgentTools." + cname not in gn:
            errs.append("guessHint 缺 %s -> 那条进度提示会掉进「正在干活」" % (cname, consts[cname]))

    # ---- 4. 危险闸门要认得下载（下载也是往盘上写）----
    if "AgentTools.DOWNLOAD" not in guard:
        errs.append("DangerGuard.risk 没处理 DOWNLOAD -> 往 sdcard 外下载不弹确认")

    # ---- 5. 围栏：Downloader 不能绕开路径检查 ----
    if "fun guardPath" not in env:
        errs.append("AgentRunner 没暴露 guardPath -> 下载绕过了沙箱路径围栏")
    dl = AG + "/Downloader.kt"
    with open(dl, encoding="utf-8") as f:
        downloader = f.read()
    if "Downloader.start(" not in tool:
        errs.append("AgentTool 里没调 Downloader.start -> 待办第 2 条还没做完（工具链没接上）")
    if "AgentRunner.guardPath" not in tool:
        errs.append("start_download 的落点没过 guardPath 围栏")
    for tname in ("start", "get", "recent", "summary"):
        if "fun " + tname not in downloader and "fun " + tname + "(" not in downloader:
            errs.append("Downloader 里没有 %s，调用点对不上" % tname)

    # ---- 6. conversationId 要从 AgentChat 传下来 ----
    call = chat.split("AgentTools.run(", 1)
    if len(call) < 2:
        errs.append("找不到 AgentTools.run 的调用点")
    elif "conversationId = conversationId" not in call[1][:1200]:
        errs.append("AgentChat 没把 conversationId 传给 AgentTools.run -> 进度会串台")

    # ---- 7. 系统提示词必须认识新工具（不然模型根本不会用）----
    for tname in ("start_download", "check_download"):
        if tname not in store:
            errs.append("系统提示词 TOOL_NOTE 里没提 %s -> 模型看不到就不会用" % tname)

    # ---- 8. ALL 列表里两个新工具都要注册 ----
    all_block = tool.split("val ALL: List<AgentTool>", 1)[-1].split("fun toolsFor", 1)[0]
    for cname in ("DOWNLOAD", "DOWNLOAD_CHECK"):
        if "name = " + cname not in all_block:
            errs.append("ALL 里没有 %s 的定义 -> toolsFor 不会把它发给模型" % cname)

    print("-" * 60)
    if errs:
        print("✗ 抓到 %d 个问题：" % len(errs))
        for e in errs:
            print("   · " + e)
        return 1
    print("✅ 全部通过：两个新工具在 4 处登记齐了，围栏 / 危险闸门 / 提示词 / 进度上报都接上了")
    print("   （但这**不等于能编译** —— 本地没有 Android SDK，最终还得看 CI）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
