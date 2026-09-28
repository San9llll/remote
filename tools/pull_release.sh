#!/bin/sh
# ============================================================
# 从 GitHub 拉最新的 release APK，放进插件的更新目录（冷包）
#
# 用法：  sh pull_release.sh            # 拉最新 tag
#         sh pull_release.sh v9.0       # 拉指定 tag
#
# 放好之后，App 里点「检查更新」就能看到冷更新。
# ============================================================

REPO="San9llll/shizuku-app"
UPDATES="/AstrBot/data/plugin_data/astrbot_plugin_supervisor/updates"
COLD="$UPDATES/cold"
ASSET="shizuku-app.apk"

TOKEN="${GH_TOKEN:-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}"

TAG="$1"
mkdir -p "$COLD"

if [ -z "$TAG" ]; then
  echo "→ 查最新 release…"
  TAG=$(curl -s -m 30 -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/releases?per_page=1" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['tag_name'])")
fi
echo "→ 目标: $TAG"

AID=$(curl -s -m 30 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/$REPO/releases/tags/$TAG" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
for a in d.get('assets', []):
    if a['name'] == '$ASSET':
        print(a['id']); break
")
if [ -z "$AID" ]; then
  echo "✗ 这个 tag 里没有 $ASSET"
  exit 1
fi

TS=$(date +%Y%m%d_%H%M)
VER=$(echo "$TAG" | sed 's/^v//')
DST="$COLD/app_${VER}_${TS}.apk"
echo "→ 下载到 $DST"

i=1
while [ $i -le 3 ]; do
  echo "  第 $i 次尝试…"
  curl -L --retry 3 --retry-delay 2 -m 900 \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/octet-stream" \
    "https://api.github.com/repos/$REPO/releases/assets/$AID" \
    -o "$DST"
  if python3 -c "
import zipfile, sys
try:
    zipfile.ZipFile('$DST'); sys.exit(0)
except Exception:
    sys.exit(1)
"; then
    echo "✓ 下载完成并校验通过"
    ls -la "$DST"
    ls -t "$COLD"/*.apk 2>/dev/null | tail -n +4 | xargs -r rm -f
    echo "→ 已清理旧包，当前目录:"
    ls -la "$COLD"
    exit 0
  fi
  i=$((i + 1))
done

echo "✗ 三次都没下完（网络问题），可稍后重跑"
exit 1
