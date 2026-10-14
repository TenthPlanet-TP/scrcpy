#!/bin/bash
# 一键生成 release 包(参考 scrcpy 官方发布包结构,平铺)
#
# 产物: release/output/scrcpy-linux-<arch>-<version>.tar.gz (+ .sha256)
#   包内: scrcpy  scrcpy-server  scrcpy.1  icon.png  LICENSE
#         jniLibs/<abi>/libwebrtcLibsJni.so (QP 统计,缺失时 qp=-1)
#         scripts/run_monitor.sh  example/{app.js,package.json,public/}  # 监控链路
# 用法: scripts/make_release.sh [version]   # 缺省取 git describe
#
# 说明:
# - 客户端用系统依赖(SDL2/FFmpeg)动态链接 + portable=true(优先从可执行文件
#   同目录找 scrcpy-server,与官方发布包行为一致);全静态构建走 release/build_all.sh
# - adb 仅官方 macOS 包携带,此处不打包;linux 用户用系统 adb 即可
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-$(git describe --tags --always 2>/dev/null || true)}"
[[ -n "$VERSION" ]] || VERSION="v3.2"
ARCH="$(uname -m)"
OUT_DIR="release/output"
BUILD_DIR="build-release"
STAGE_DIR="$OUT_DIR/stage/scrcpy-linux-$ARCH-$VERSION"

for cmd in meson ninja zip; do
    command -v "$cmd" >/dev/null || { echo "缺少 $cmd" >&2; exit 1; }
done
[[ -x ./gradlew ]] || { echo "缺少 gradlew" >&2; exit 1; }

echo "[1/4] 构建 server jar..."
./gradlew -p server assembleRelease -q
SERVER_APK=server/build/outputs/apk/release/server-release-unsigned.apk
[[ -f "$SERVER_APK" ]] || { echo "未找到 $SERVER_APK" >&2; exit 1; }

echo "[2/4] 构建 linux 客户端(release + lto + portable)..."
rm -rf "$BUILD_DIR"
meson setup "$BUILD_DIR" --buildtype=release --strip -Db_lto=true \
    -Dcompile_server=false -Dportable=true -Dv4l2=false >/dev/null
ninja -C "$BUILD_DIR" >/dev/null

echo "[3/4] 组装包内容..."
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
cp "$BUILD_DIR/app/scrcpy"            "$STAGE_DIR/"
cp "$SERVER_APK"                      "$STAGE_DIR/scrcpy-server"
cp app/scrcpy.1                       "$STAGE_DIR/"
cp app/data/icon.png                  "$STAGE_DIR/"
cp LICENSE                            "$STAGE_DIR/"
mkdir -p "$STAGE_DIR/scripts"
cp scripts/run_monitor.sh            "$STAGE_DIR/scripts/"
mkdir -p "$STAGE_DIR/example"
cp example/app.js example/package.json "$STAGE_DIR/example/"
cp -r example/public                 "$STAGE_DIR/example/public"
cp -r server/src/main/jniLibs         "$STAGE_DIR/jniLibs"

echo "[4/4] 打包与校验和..."
mkdir -p "$OUT_DIR"
PKG="$OUT_DIR/scrcpy-linux-$ARCH-$VERSION.tar.gz"
rm -f "$PKG" "$PKG.sha256"
tar -C "$(dirname "$STAGE_DIR")" -czf "$PKG" "$(basename "$STAGE_DIR")"
sha256sum "$PKG" | sed "s|$OUT_DIR/||" > "$PKG.sha256"
rm -rf "$OUT_DIR/stage"

echo "完成: $PKG"
ls -lh "$PKG"
