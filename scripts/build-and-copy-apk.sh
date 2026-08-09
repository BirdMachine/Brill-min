#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/gradlew" -p "$ROOT" assembleDebug
mkdir -p "$ROOT/dist"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/dist/BrillBody-debug.apk"
echo "Built: $ROOT/dist/BrillBody-debug.apk"
