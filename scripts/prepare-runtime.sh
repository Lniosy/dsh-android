#!/usr/bin/env bash
# 把 node + DSH 内核放到 App 可加载的 payload 目录。
# 用法：
#   bash scripts/prepare-runtime.sh
# 然后用 adb push runtime/payload /data/data/com.zsdsh.dsh/files/payload
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/runtime/payload"
mkdir -p "$OUT/runtime" "$OUT/dshhome" "$OUT/dshroot"

echo "期望目录："
echo "  $OUT/runtime/<abi>/bin/node   abi=arm64-v8a|x86_64|x86|armeabi-v7a"
echo "  $OUT/runtime/<abi>/lib/*.so"
echo "  $OUT/dshroot/                  @deepseek-ai/dsh 解包结果"
echo "  $OUT/dshhome/                  空目录即可，凭证运行时写入"
echo
echo "雷电模拟器请放 x86_64；真机请放 arm64-v8a。"
echo "社区 APK 自带 node 是 aarch64，雷电上会 Exec format error。"

PLUGIN_SRC="$ROOT/plugins/dsh-tool-android"
PLUGIN_DST="$OUT/dshroot/lib/node_modules/@zsdsh/dsh-tool-android"
if [[ -f "$PLUGIN_SRC/lib/index.js" ]]; then
  mkdir -p "$PLUGIN_DST"
  cp -R "$PLUGIN_SRC/." "$PLUGIN_DST/"
  echo "已复制插件到 $PLUGIN_DST"
fi

if [[ -f "$ROOT/config/cordis.patch.yml" ]]; then
  mkdir -p "$OUT/dshhome"
  cp "$ROOT/config/cordis.patch.yml" "$OUT/dshhome/cordis.patch.yml"
  echo "已复制 cordis.patch.yml"
fi
