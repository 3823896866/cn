#!/usr/bin/env bash
# 交叉编译 FreeType 2.13.2 (arm64-v8a, android-21, 关 harfbuzz/bzip2/png/brotli/zlib)
# 产物：本目录 libfreetype.a，供 CMakeLists.txt 链接。
# 用法：bash build_freetype_aarch64.sh <ndk根目录>
set -euo pipefail
NDK="${1:?用法: bash build_freetype_aarch64.sh <ndk根目录>}"
API="${API:-21}"
BUILD_DIR="$(pwd)/.freetype-build"
SRC="$BUILD_DIR/freetype"
VER="2.13.2"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || { echo "找不到 $TOOLCHAIN"; exit 1; }

rm -rf "$BUILD_DIR"; mkdir -p "$BUILD_DIR"
if [ ! -d "$SRC" ]; then
  echo "==> 下载 FreeType $VER"
  curl -L "https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.gz" -o "$BUILD_DIR/ft.tgz"
  tar xzf "$BUILD_DIR/ft.tgz" -C "$BUILD_DIR"
fi
CFG="$BUILD_DIR/ft.cfg"; mkdir -p "$CFG"
echo "==> 配置 FreeType (aarch64, android-$API)"
cmake "$SRC" -B "$CFG" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_PNG=OFF -DENABLE_BZIP2=OFF -DENABLE_BROTLI=OFF \
  -DENABLE_HARFBUZZ=OFF -DWITH_ZLIB=OFF \
  -DCMAKE_INSTALL_PREFIX="$CFG/install"
echo "==> 编译 + 安装"
cmake --build "$CFG" -j"$(nproc)"
cmake --install "$CFG" --component freetype
cp "$CFG/install/lib/libfreetype.a" "$(pwd)/libfreetype.a"
echo "==> 完成：$(pwd)/libfreetype.a"
