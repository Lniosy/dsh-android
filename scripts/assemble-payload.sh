#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/runtime/work"
OUT="$ROOT/runtime/payload"
KEY_FILE="$ROOT/runtime/.credentials.yaml"

mkdir -p "$OUT/runtime" "$OUT/dshhome" "$OUT/dshroot"

if [[ ! -d "$WORK/node_modules/@deepseek-ai/dsh" ]]; then
  echo "先在 runtime/work 安装 @deepseek-ai/dsh@0.1.5-rc.1" >&2
  exit 1
fi

rsync -a --delete \
  --exclude '.bin' \
  "$WORK/node_modules/" "$OUT/dshroot/node_modules/"

mkdir -p "$OUT/dshroot/node_modules/@zsdsh"
rsync -a "$ROOT/plugins/dsh-tool-android/" "$OUT/dshroot/node_modules/@zsdsh/dsh-tool-android/"

node "$ROOT/scripts/apply-android-patches.mjs" "$OUT/dshroot"
cp "$ROOT/config/cordis.patch.yml" "$OUT/dshhome/cordis.patch.yml"
mkdir -p "$OUT/dshroot/node_modules/node-pty" "$OUT/dshroot/node_modules/koffi"
cp "$ROOT/shims/node-pty/index.js" "$OUT/dshroot/node_modules/node-pty/index.js"
cp "$ROOT/shims/node-pty/package.json" "$OUT/dshroot/node_modules/node-pty/package.json"
cp "$ROOT/shims/koffi/index.js" "$OUT/dshroot/node_modules/koffi/index.js"

if [[ -f "$KEY_FILE" ]]; then
  cp "$KEY_FILE" "$OUT/dshhome/.credentials.yaml"
  chmod 600 "$OUT/dshhome/.credentials.yaml"
  echo "已放入测试凭据（仅本地 payload，不进 APK）"
else
  echo "未找到 runtime/.credentials.yaml，启动后需在 Web UI 里填 Key"
fi

test -x "$OUT/runtime/arm64-v8a/bin/node" || echo "警告：缺少 arm64-v8a node"
echo "payload 就绪：$OUT"
du -sh "$OUT" "$OUT/dshroot" "$OUT/runtime" 2>/dev/null || true
