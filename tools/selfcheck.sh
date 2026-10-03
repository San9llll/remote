#!/bin/sh
# ============================================================
# 推代码前的本地自查（不用 JDK，只做静态粗查）
#
# 为什么要有它：本地没有 Android SDK/JDK，编译只能在 GitHub Actions 上跑，
# 而 CI 一轮要几分钟 —— 这个脚本把"一眼能看出来的错"先挡掉。
#
# 用法：  sh tools/selfcheck.sh     （在工程根目录跑）
#
# 误报修正记录：
#   v1 把 ui.component 下的组件当成 ui.common 的，误报四次
#   v2 同包/同文件定义的符号被当成"忘 import"，误报两次
#   v3 只查组件名，漏了 asImageBitmap / fillMaxSize 这类扩展函数（CI 又红两轮）
#   v4 把作用域成员（matchParentSize）和全限定调用也算成漏 import，又误报两条
#   v5 补上 padding 重载混用检查（horizontal+top 这种，CI 报 None of the candidates）
#   v6 补上 by 委托的 import 检查（漏 getValue/setValue 会报 Property delegate must have...）
#      委托属性那条不看是否真访问成员，误报三次
# ============================================================

SRC="app/src/main/java"
FAIL=0

say() { printf '%s\n' "$1"; }

say "== ① 包名/目录一致性 =="
for f in $(find "$SRC" -name "*.kt"); do
  pkg=$(grep -m1 '^package' "$f" | awk '{print $2}')
  dir=$(dirname "$f" | sed "s|$SRC/||" | tr '/' '.')
  if [ "$pkg" != "$dir" ]; then
    say "   ✗ $f  package=$pkg  目录=$dir"
    FAIL=1
  fi
done
[ "$FAIL" = "0" ] && say "   OK"

say "== ② 典型手误（import 粘行 / 包名少个 g / 残留旧包名）=="
BAD=$(grep -rn "importimport\|top\.yukongai\|com\.shizuku\.app" "$SRC" 2>/dev/null)
if [ -n "$BAD" ]; then
  say "$BAD" | sed 's/^/   ✗ /'
  FAIL=1
else
  say "   OK"
fi

say "== ③ 组件有没有忘了 import =="
# 判据（避免误报）：
#   a) 已经 import 了 → OK
#   b) 在本文件里定义 → OK
#   c) 定义在**同一个包**的别的文件里 → OK（Kotlin 同包不用 import）
#   d) 都不满足才算漏
check_sym() {
  f="$1"; sym="$2"
  # 只看代码行（跳过 // 注释），免得注释里提一句就被当成"用了"
  used=$(grep -vE "^\s*//" "$f" | grep -c "\b$sym\b")
  [ "$used" -eq 0 ] && return 0
  grep -q "import .*\.$sym$" "$f" && return 0
  grep -qE "^\s*(private |internal |public )?(fun|val|class|object|data class) $sym\b" "$f" && return 0
  # 同包：看看本文件所在目录里有没有别的文件定义了它
  dir=$(dirname "$f")
  if grep -rqE "^\s*(private |internal |public )?(fun|val|class|object|data class) $sym\b" "$dir" 2>/dev/null; then
    return 0
  fi
  say "   ✗ $(echo "$f" | sed "s|$SRC/||") 用了 $sym 但没 import（也不在同包）"
  FAIL=1
}
for f in $(find "$SRC" -name "*.kt"); do
  for sym in GroupCard InfoRow PageHeader SectionTitle ActionRow CountTile Option OptionDialog \
             WarningCard GlassCard IndicatorSwitch IndicatorSwitchPreference ModuleBadge \
             ModuleCardRow ModuleIconAction CollapsedRow ContentBackdrop HomeSceneBackdrop \
             asImageBitmap fillMaxSize fillMaxWidth fillMaxHeight \
             animateFloatAsState animateDpAsState              clickable verticalScroll rememberScrollState windowInsetsPadding; do
    check_sym "$f" "$sym"
  done
done

say "== ④ 委托属性智能转换（Kotlin 老坑）=="
# 只在**同一行**里同时出现 «x?.y == true» 和 «x.z»（成员访问）才算有问题；
# 后面跟字符串字面量、或者下一行才用 x 的，编译器不会报。
FOUND=0
for f in $(find "$SRC" -name "*.kt"); do
  while IFS= read -r line; do
    var=$(printf '%s' "$line" | sed -nE 's/.*[^a-zA-Z_]([a-zA-Z_]+) ?\?\.[A-Za-z]+ == true.*/\1/p')
    [ -z "$var" ] && continue
    # 同一行里有没有 var.成员 这种直接访问
    printf '%s' "$line" | grep -qE "[^a-zA-Z_]$var\.[a-zA-Z_]" || continue
    if grep -qE "^\s*(var|val) $var by " "$f"; then
      say "   ✗ $(echo "$f" | sed "s|$SRC/||"): $line"
      FAIL=1
      FOUND=1
    fi
  done <<EOF
$(grep -nE "[a-zA-Z_]+ ?\?\.[A-Za-z]+ == true" "$f" 2>/dev/null)
EOF
done
[ "$FOUND" = "0" ] && say "   OK"

say "== ⑤ by 委托的 import =="
# 用了 by remember / by mutableStateOf 却没 import getValue/setValue —— CI 会报
# "Property delegate must have a 'getValue(...)' method"
DELEG=$(for f in $(find "$SRC" -name "*.kt"); do
  if grep -qE "\bby\s+(remember|mutableStateOf)" "$f"; then
    if ! grep -q "import androidx.compose.runtime.getValue" "$f"; then
      echo "   ✗ $(echo "$f" | sed "s|$SRC/||") 用了 by 但没 import getValue"
    fi
    if ! grep -q "import androidx.compose.runtime.setValue" "$f" && grep -qE "var .* by " "$f"; then
      echo "   ✗ $(echo "$f" | sed "s|$SRC/||") 用了 var ... by 但没 import setValue"
    fi
  fi
done)
if [ -n "$DELEG" ]; then
  say "$DELEG"
  FAIL=1
else
  say "   OK"
fi

say "== ⑥ padding 重载混用 =="
# Compose 的 Modifier.padding 只有两种：
#   (start, top, end, bottom)  或  (horizontal, vertical)
# 写成 (horizontal, top) 之类哪个签名都对不上 —— CI 会报 None of the candidates
MIX=$(grep -rnE "padding\(\s*horizontal\s*=[^)]*,\s*(top|bottom|start|end)\s*=|padding\(\s*vertical\s*=[^)]*,\s*(start|end)\s*=|padding\(\s*(top|bottom|start|end)\s*=[^)]*,\s*horizontal\s*=" "$SRC" 2>/dev/null)
if [ -n "$MIX" ]; then
  say "$MIX" | sed "s|$SRC/||" | sed 's/^/   ✗ /'
  FAIL=1
else
  say "   OK"
fi


# ============================================================
# ⑦ var 属性的隐式 setter vs 手写的 setXxx
#
# Kotlin 里 `var x by mutableStateOf(...)` 会生成 setX(...)。
# 如果你还手写一个同名同参的 fun setX(...)，编译期报
#   Platform declaration clash: ... same JVM signature
# 这坑踩过两次（toolProgress / builtinHero），所以加一道自动检查。
# ============================================================
say "== ⑦ var 的隐式 setter 有没有和手写 setXxx 撞名 =="
CLASH=$(python3 - <<'PYEOF'
import os, re
SRC = "app/src/main/java/lo/naui"
for root, _, files in os.walk(SRC):
    for fn in files:
        if not fn.endswith(".kt"): continue
        fp = os.path.join(root, fn)
        src = open(fp, encoding="utf-8").read()
        props = set()
        for m in re.finditer(r"\bvar (\w+) by ", src):
            n = m.group(1)
            props.add("set" + n[0].upper() + n[1:])
        for m in re.finditer(r"\bfun (set\w+)\s*\(([^)]*)\)", src):
            name, params = m.group(1), m.group(2)
            if name in props and params.count(",") == 0:
                line = src[:m.start()].count("\n") + 1
                print("%s:%d  %s(%s)" % (fp.replace(SRC + "/", ""), line, name, params))
PYEOF
)
if [ -n "$CLASH" ]; then
  say "$CLASH" | sed 's/^/   ✗ /'
  say "   （属性的隐式 setter 是 setXxx，手写同名同参的函数会撞 JVM 签名）"
  FAIL=1
else
  say "   OK"
fi

say "== ⑥ 路由分发有没有漏模块 =="
DETAIL="$SRC/com/nanux/ui/modules/ModuleDetailScreen.kt"
if [ -f "$DETAIL" ]; then
  for k in supervisor weather voice_clone maintenance daily video memory persona stats doctor; do
    if ! grep -q "\"$k\"" "$DETAIL"; then
      say "   ⚠ 模块 $k 没有分发（会落到占位页）"
    fi
  done
  say "   （⚠ 只是提示，不算硬问题）"
fi

say ""
if [ "$FAIL" = "0" ]; then
  say "✅ 自查通过（不等于能编译，只是把常见错挡在推之前）"
else
  say "❌ 有硬问题，先修再推"
fi
exit "$FAIL"
