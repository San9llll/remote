#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
推送前的硬检查 —— 比 selfcheck 更狠一层。

selfcheck 管的是"这个组件忘没忘 import"这种；
这个脚本管的是**跨文件对不上**的错，也就是最近连着红了好几次的那类：

  1. data class 的字段，和所有构造它的地方，对不对得上
  2. 顶层函数/类的**全限定名当扩展函数用**（Kotlin 不允许）
  3. 函数新增参数后，调用点有没有传对名字
  4. 括号平衡 / 顶层函数重名
  5. 在 DrawScope（onDrawSurface / Canvas / drawWithContent）里读 MiuixTheme
"""
import os
import re
import sys
from collections import defaultdict

SRC = "app/src/main/java/lo/naui"

problems = []
warns = []


def read_all():
    out = {}
    for root, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                p = os.path.join(root, f)
                out[p] = open(p, encoding="utf-8").read()
    return out


FILES = read_all()


def strip_code(s):
    """去掉字符串和注释，留着数括号用"""
    s = re.sub(r'"""[\s\S]*?"""', '""', s)
    s = re.sub(r'"(\\.|[^"\\])*"', '""', s)
    s = re.sub(r"'(\\.|[^'\\])*'", "''", s)
    s = re.sub(r"//[^\n]*", "", s)
    s = re.sub(r"/\*[\s\S]*?\*/", "", s)
    return s


# ---------- ① data class 的字段 vs 构造点 ----------
def check_data_classes():
    decls = {}   # 名字 -> (文件, 参数集合, 有没有默认值)
    for path, src in FILES.items():
        for m in re.finditer(r"\bdata class (\w+)\s*\(([\s\S]*?)\n\)", src):
            name = m.group(1)
            body = m.group(2)
            params = set()
            for line in body.split("\n"):
                pm = re.match(r"\s*val (\w+)", line)
                if pm:
                    params.add(pm.group(1))
            if params:
                decls[name] = (path, params)

    # 找所有 "XXX(" 的具名构造（只查我们自己的 data class）
    for path, src in FILES.items():
        for name, (decl_path, params) in decls.items():
            for m in re.finditer(r"\b" + name + r"\(([^()]*(?:\([^()]*\)[^()]*)*)\)", src):
                args = m.group(1)
                # 只认 "名字 = 值" 这种具名参数，而且要排除:
                #   - 类型标注（val x: Type = ...）
                #   - lambda 里的等号
                used = set()
                for am in re.finditer(r"(?:^|,)\s*(\w+)\s*=(?!=)", args):
                    name = am.group(1)
                    # 前面不能是 ":"（那是类型标注）
                    before = args[max(0, am.start(1) - 2):am.start(1)]
                    if ":" in before:
                        continue
                    if name in ("val", "var", "if", "while", "return", "else"):
                        continue
                    used.add(name)
                if not used:
                    continue
                bad = used - params
                if bad:
                    line = src[:m.start()].count("\n") + 1
                    problems.append(
                        "%s:%d  %s 没有这些字段: %s（声明在 %s）"
                        % (path.replace(SRC + "/", ""), line, name, ", ".join(sorted(bad)),
                           decl_path.replace(SRC + "/", ""))
                    )


# ---------- ② 全限定名当扩展函数用 ----------
def check_qualified_extension():
    KNOWN = (
        "androidx.compose.foundation.clickable",
        "androidx.compose.foundation.background",
        "androidx.compose.foundation.border",
        "androidx.compose.ui.draw.clip",
        "androidx.compose.ui.draw.alpha",
        "androidx.compose.ui.draw.blur",
        "androidx.compose.ui.graphics.graphicsLayer",
        "androidx.compose.foundation.layout.padding",
        "androidx.compose.foundation.layout.height",
        "androidx.compose.foundation.layout.width",
        "androidx.compose.foundation.layout.fillMaxWidth",
        "androidx.compose.foundation.layout.fillMaxSize",
        "androidx.compose.foundation.layout.size",
        "androidx.compose.foundation.layout.imePadding",
        "androidx.compose.foundation.layout.windowInsetsPadding",
        "androidx.compose.foundation.layout.statusBarsPadding",
    )
    for path, src in FILES.items():
        for ext in KNOWN:
            if ext + "(" in src:
                line = src[:src.index(ext + "(")].count("\n") + 1
                problems.append(
                    "%s:%d  扩展函数不能用全限定名调：%s(  → 先 import 再用"
                    % (path.replace(SRC + "/", ""), line, ext)
                )


# ---------- ③ 括号平衡 ----------
def check_balance():
    for path, src in FILES.items():
        s = strip_code(src)
        for open_c, close_c, nm in (("{", "}", "花括号"), ("(", ")", "圆括号")):
            a, b = s.count(open_c), s.count(close_c)
            if a != b:
                problems.append(
                    "%s  %s不平衡：%d 个 %s vs %d 个 %s"
                    % (path.replace(SRC + "/", ""), nm, a, open_c, b, close_c)
                )


# ---------- ④ 顶层函数重名（同包） ----------
def check_duplicate_toplevel():
    by_pkg = defaultdict(lambda: defaultdict(list))
    for path, src in FILES.items():
        pm = re.search(r"^package\s+([\w.]+)", src, re.M)
        if not pm:
            continue
        pkg = pm.group(1)
        for m in re.finditer(r"^(?:@\w+(?:\([^)]*\))?\s*\n)*\s*(?:private |internal |public )?fun (\w+)\(", src, re.M):
            by_pkg[pkg][m.group(1)].append(path)
    for pkg, funcs in by_pkg.items():
        for fn, paths in funcs.items():
            if len(paths) > 1:
                # 同名 private 在不同文件是允许的（文件私有），所以只提醒
                srcs = [open(p, encoding="utf-8").read() for p in paths]
                all_private = all(
                    re.search(r"^private fun %s\(" % fn, s, re.M) for s in srcs
                )
                if not all_private:
                    warns.append(
                        "同包下重名函数 '%s'（非全 private）：%s"
                        % (fn, " | ".join(p.replace(SRC + "/", "") for p in paths))
                    )


# ---------- ⑤ DrawScope 里读 MiuixTheme ----------
def check_drawscope_theme():
    PATTERNS = [
        (r"onDrawSurface\s*=\s*\{([^}]*)\}", "onDrawSurface"),
        (r"drawWithContent\s*\{([\s\S]{0,600}?)\n\s*\}", "drawWithContent"),
    ]
    for path, src in FILES.items():
        for pat, nm in PATTERNS:
            for m in re.finditer(pat, src):
                if "MiuixTheme" in m.group(1):
                    line = src[:m.start()].count("\n") + 1
                    problems.append(
                        "%s:%d  %s 是 DrawScope，里头读不了 MiuixTheme —— 在外面先取好"
                        % (path.replace(SRC + "/", ""), line, nm)
                    )


# ---------- ⑥ by 委托的 import ----------
def check_delegate_import():
    for path, src in FILES.items():
        if re.search(r"\bby remember\b", src) or re.search(r"\bby mutableStateOf\b", src):
            if "import androidx.compose.runtime.getValue" not in src:
                problems.append("%s  用了 by 但没 import getValue" % path.replace(SRC + "/", ""))
            if re.search(r"\bvar \w+ by ", src) and "import androidx.compose.runtime.setValue" not in src:
                problems.append("%s  用了 var ... by 但没 import setValue" % path.replace(SRC + "/", ""))


# ---------- ⑦ 顶层 data class 被写成 内部类.名字 ----------
def check_nested_ref():
    toplevel = set()
    for path, src in FILES.items():
        for m in re.finditer(r"^data class (\w+)", src, re.M):
            toplevel.add(m.group(1))
    # 只查"lo.naui"下的顶层类（androidx 那些不用管）
    ours = set()
    for path, src in FILES.items():
        for m in re.finditer(r"^data class (\w+)", src, re.M):
            ours.add(m.group(1))
        for m in re.finditer(r"^(?:enum class|sealed class) (\w+)", src, re.M):
            ours.add(m.group(1))
    for path, src in FILES.items():
        for name in ours:
            for m in re.finditer(r"\b(\w+)\." + name + r"\b", src):
                holder = m.group(1)
                # 常见的合法情况：枚举伴生、包名
                if holder in ("companion", "object"):
                    continue
                line = src[:m.start()].count("\n") + 1
                ctx = src.split("\n")[line - 1].strip()
                # import / package 行跳过
                if ctx.startswith("import ") or ctx.startswith("package "):
                    continue
                # 只有在同文件里没有 "object holder" / "class holder" 才算错
                if not re.search(r"\b(?:object|class|interface|enum class)\s+" + holder + r"\b", src):
                    problems.append(
                        "%s:%d  %s 是顶层类，不该写成 %s.%s"
                        % (path.replace(SRC + "/", ""), line, name, holder, name)
                    )


check_data_classes()
check_qualified_extension()
check_balance()
check_duplicate_toplevel()
check_drawscope_theme()
check_delegate_import()
check_nested_ref()

print("=" * 60)
if problems:
    print("❌ 硬问题 %d 条：" % len(problems))
    for p in sorted(set(problems)):
        print("   " + p)
else:
    print("✅ 跨文件检查通过")

if warns:
    print()
    print("⚠️  提醒 %d 条（不一定错）：" % len(warns))
    for w in sorted(set(warns)):
        print("   " + w)

print("=" * 60)
sys.exit(1 if problems else 0)
