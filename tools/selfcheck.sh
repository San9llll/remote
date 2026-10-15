#!/bin/sh
# ============================================================
#推代码前的本地自查（不用 JDK，只做静态粗查）
#
#为什么要有它：本地没有 Android SDK/JDK，编译只能在 GitHub Actions上跑，
#而 CI一轮要几分钟 ——这个脚本把"一眼能看出来的错"先挡掉。
#
#用法： sh tools/selfcheck.sh （在工程根目录跑）
#
#误报修正记录：
# v1把 ui.component下的组件当成 ui.common的，误报四次
# v2同包/同文件定义的符号被当成"忘 import"，误报两次
# v3只查组件名，漏了 asImageBitmap / fillMaxSize这类扩展函数（CI又红两轮）
# v4把作用域成员（matchParentSize）和全限定调用也算成漏 import，又误报两条
# v5补上 padding重载混用检查（horizontal+top这种，CI报 None of the candidates）
# v6补上 by委托的 import检查（漏 getValue/setValue会报 Property delegate must have...）
#委托属性那条不看是否真访问成员，误报三次
# v7清旧项目残留（1.03.0）：
# ·⑦原来跑 python3，而这台机器上没装 ——整节被静默跳过（连个提示都没有），
#看着打了 OK其实啥也没查。改成纯 awk，不依赖 python。
# ·末尾还有一节也叫⑥，查的是 com/nanux/ui/modules/ModuleDetailScreen.kt，
#那是**别的项目**的路径，在这工程里永远不命中 ——
#一节永远不跑的检查比没有更糟，直接删掉。
# ·③的符号表里 ModuleBadge / ModuleCardRow / ModuleIconAction /
# CollapsedRow也是旧项目留下的，这工程里一个都没有，一并清掉。
# v8⑦改两遍扫描（先攒全文的 var，再比 fun）——
#单遍走的话，`fun setX`写在 `var x by`前面就漏了。
# ⚠️写这个脚本时踩的坑：文件本身有一行 `used=$(grep -c ... "[^"]")`，
#用 sed改别的行时被当成模式吃掉了。改脚本一律用 heredoc重写，别 sed。
# ============================================================

SRC="app/src/main/java"
FAIL=0

say() { printf '%s\n' "$1"; }

say "==①包名/目录一致性 =="
for f in $(find "$SRC" -name "*.kt"); do
 pkg=$(grep -m1 '^package' "$f" | awk '{print $2}')
 dir=$(dirname "$f" | sed "s|$SRC/||" | tr '/' '.')
 if [ "$pkg" != "$dir" ]; then
 say " ✗ $f package=$pkg目录=$dir"
 FAIL=1
 fi
done
[ "$FAIL" = "0" ] && say " OK"

say "==②典型手误（import粘行 /包名少个 g /残留旧包名）=="
BAD=$(grep -rn "importimport\|top\.yukongai\|com\.shizuku\.app" "$SRC"2>/dev/null)
if [ -n "$BAD" ]; then
 say "$BAD" | sed 's/^/ ✗ /'
 FAIL=1
else
 say " OK"
fi

say "==③组件有没有忘了 import =="
#判据（避免误报）：
# a)已经 import了 → OK
# b)在本文件里定义 → OK
# c)定义在**同一个包**的别的文件里 → OK（Kotlin同包不用 import）
# d)都不满足才算漏
check_sym() {
 f="$1"; sym="$2"
 #只看代码行（跳过 //注释），免得注释里提一句就被当成"用了"
 used=$(grep -vE "^[[:space:]]*//" "$f" | grep -c "\b$sym\b")
 [ "$used" -eq "0" ] && return
 grep -q "import .*\.$sym$" "$f" && return
 grep -qE "^[[:space:]]*(private |internal |public )?(fun|val|class|object|data class) $sym\b" "$f" && return
 #同包：看看本文件所在目录里有没有别的文件定义了它
 dir=$(dirname "$f")
 if grep -rqE "^[[:space:]]*(private |internal |public )?(fun|val|class|object|data class) $sym\b" "$dir"2>/dev/null; then
 return
 fi
 say " ✗ $(echo "$f" | sed "s|$SRC/||")用了 $sym但没 import（也不在同包）"
 FAIL=1
}
for f in $(find "$SRC" -name "*.kt"); do
 for sym in GroupCard InfoRow PageHeader SectionTitle ActionRow CountTile Option OptionDialog \
 WarningCard GlassCard IndicatorSwitch IndicatorSwitchPreference \
 ContentBackdrop HomeSceneBackdrop \
 asImageBitmap fillMaxSize fillMaxWidth fillMaxHeight \
 animateFloatAsState animateDpAsState \
 clickable verticalScroll rememberScrollState windowInsetsPadding; do
 check_sym "$f" "$sym"
 done
done

say "==④委托属性智能转换（Kotlin老坑）=="
#只在**同一行**里同时出现 «x?.y == true»和 «x.z»（成员访问）才算有问题；
#后面跟字符串字面量、或者下一行才用 x的，编译器不会报。
FOUND=0
for f in $(find "$SRC" -name "*.kt"); do
 while IFS= read -r line; do
 var=$(printf '%s' "$line" | sed -nE 's/.*[^a-zA-Z_]([a-zA-Z_]+) ?\?\.[A-Za-z]+ == true.*/\1/p')
 [ -z "$var" ] && continue
 #同一行里有没有 var.成员这种直接访问
 printf '%s' "$line" | grep -qE "[^a-zA-Z_]$var\.[a-zA-Z_]" || continue
 if grep -qE "^[[:space:]]*(var|val) $var by " "$f"; then
 say " ✗ $(echo "$f" | sed "s|$SRC/||"): $line"
 FAIL=1
 FOUND=1
 fi
 done <<EOF
$(grep -nE "[a-zA-Z_]+ ?\?\.[A-Za-z]+ == true" "$f"2>/dev/null)
EOF
done
[ "$FOUND" = "0" ] && say " OK"

say "==⑤ by委托的 import =="
#用了 by remember / by mutableStateOf却没 import getValue/setValue —— CI会报
# "Property delegate must have a 'getValue(...)' method"
DELEG=$(for f in $(find "$SRC" -name "*.kt"); do
 if grep -qE "\bby[[:space:]]+(remember|mutableStateOf)" "$f"; then
 if ! grep -q "import androidx.compose.runtime.getValue" "$f"; then
 echo " ✗ $(echo "$f" | sed "s|$SRC/||")用了 by但没 import getValue"
 fi
 if ! grep -q "import androidx.compose.runtime.setValue" "$f" && grep -qE "var .* by " "$f"; then
 echo " ✗ $(echo "$f" | sed "s|$SRC/||")用了 var ... by但没 import setValue"
 fi
 fi
done)
if [ -n "$DELEG" ]; then
 say "$DELEG"
 FAIL=1
else
 say " OK"
fi

say "==⑥ padding重载混用 =="
# Compose的 Modifier.padding只有两种：
# (start, top, end, bottom)或 (horizontal, vertical)
#写成 (horizontal, top)之类哪个签名都对不上 —— CI会报 None of the candidates
MIX=$(grep -rnE "padding\(\s*horizontal\s*=[^)]*,\s*(top|bottom|start|end)\s*=|padding\(\s*vertical\s*=[^)]*,\s*(start|end)\s*=|padding\(\s*(top|bottom|start|end)\s*=[^)]*,\s*horizontal\s*=" "$SRC"2>/dev/null)
if [ -n "$MIX" ]; then
 say "$MIX" | sed "s|$SRC/||" | sed 's/^/ ✗ /'
 FAIL=1
else
 say " OK"
fi

# ============================================================
#⑦ var属性的隐式 setter vs手写的 setXxx
#
# Kotlin里 `var x by mutableStateOf(...)`会生成 setX(...)。
#如果你还手写一个同名同参的 fun setX(...)，编译期报
# Platform declaration clash: ... same JVM signature
#这坑踩过两次（toolProgress / builtinHero），所以加一道自动检查。
#
# ⚠️1.03.0之前这节是 python3 heredoc ——本地没 python3的时候，
# `$( )`拿到空串，于是**静默跳过还打 OK**。现在纯 awk：
#第一遍记下每个文件里所有 `var xxx by ...`（拼出 setXxx），
#第二遍再比每个 `fun setXxx(`（只看这一行有没有逗号 =单参）。
#两遍扫描是必须的 ——单遍走的话，setX写在 var前面就漏了。
#参数个数只看到第一个括号就够了，要挡的就是单参撞名。
# ============================================================
say "==⑦ var的隐式 setter有没有和手写 setXxx撞名 =="
CLASH=$(find "$SRC" -name "*.kt" -exec awk '
{
 line[++n] = $0
 file[n] = FILENAME
 #① var名 →推出隐式 setter名
 if (match($0, /var[ \t]+[A-Za-z_][A-Za-z0-9_]*[ \t]+by[ \t]/)) {
 s = substr($0, RSTART, RLENGTH)
 gsub(/^var[ \t]+/, "", s)
 gsub(/[ \t]+by[ \t]$/, "", s)
 props[FILENAME "|" "set" toupper(substr(s,1,1)) substr(s,2)] =1
 }
 #② fun setXxx(
 if (match($0, /fun[ \t]+set[A-Za-z0-9_]*/)) {
 fn = substr($0, RSTART, RLENGTH)
 sub(/^fun[ \t]+/, "", fn)
 funidx[++m] = n
 funname[m] = fn
 }
}
END {
 for (i =1; i <= m; i++) {
 idx = funidx[i]
 if ((file[idx] "|" funname[i]) in props) {
 #单参才可能撞（多参的 setter签名不一样）
 if (index(line[idx], ",") ==0)
 printf "%s:%d %s\n", file[idx], idx, line[idx]
 }
 }
}
' {} +)
if [ -n "$CLASH" ]; then
 say "$CLASH" | sed "s|$SRC/||" | sed 's/^/ ✗ /'
 say " （属性的隐式 setter是 setXxx，手写同名同参的函数会撞 JVM签名）"
 FAIL=1
else
 say " OK"
fi

say ""
if [ "$FAIL" = "0" ]; then
 say "✅自查通过（不等于能编译，只是把常见错挡在推之前）"
else
 say "❌有硬问题，先修再推"
fi
exit "$FAIL"
