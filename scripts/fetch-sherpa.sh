#!/usr/bin/env bash
# 下載 sherpa-onnx 的 arm64 原生函式庫（離線語音辨識用）到 app/src/main/jniLibs。
# 編譯前執行一次；GitHub Actions 會自動執行。
set -euo pipefail
V=2.0.0
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
if [ -f "$DEST/libsherpa-onnx-jni.so" ] && [ -f "$DEST/libonnxruntime.so" ]; then
  echo "sherpa-onnx libs already present"; exit 0
fi
TMP="$(mktemp -d)"
curl -fL --retry 3 -o "$TMP/android.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-v$V-android.tar.bz2"
tar xjf "$TMP/android.tar.bz2" -C "$TMP" \
  ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so ./jniLibs/arm64-v8a/libonnxruntime.so
cp "$TMP"/jniLibs/arm64-v8a/*.so "$DEST/"
rm -rf "$TMP"
ls -l "$DEST"
