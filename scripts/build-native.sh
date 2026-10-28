#!/usr/bin/env bash
# Builds the FreeRDP native libraries for Android and copies them into
# rdpcore/src/main/jniLibs/<abi>/. Dependencies (OpenSSL) are downloaded and
# built by FreeRDP's CMake superbuild on the first run.
#
# Usage: scripts/build-native.sh [abi]   (default abi: arm64-v8a)
# Env:   ANDROID_SDK_ROOT (default: $HOME/android-sdk)
set -euo pipefail

ABI="${1:-arm64-v8a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
NDK="$SDK/ndk/29.0.13113456"
CMAKE_BIN="$SDK/cmake/4.1.2/bin"
SRC="$ROOT/third_party/freerdp"
BUILD="$ROOT/build/native-$ABI"
OUT="$ROOT/rdpcore/src/main/jniLibs/$ABI"

for p in "$NDK" "$CMAKE_BIN" "$SRC"; do
	[[ -e "$p" ]] || { echo "missing: $p" >&2; exit 1; }
done

export PATH="$CMAKE_BIN:$PATH"

# Local fixes to FreeRDP's Android JNI code (scripts/patches/*.patch).
# Each patch is applied once; a patch that is already in place is skipped.
for patch in "$ROOT"/scripts/patches/*.patch; do
	[[ -e "$patch" ]] || continue
	if git -C "$SRC" apply --reverse --check "$patch" 2>/dev/null; then
		echo "already applied: $(basename "$patch")"
	else
		git -C "$SRC" apply "$patch"
		echo "applied: $(basename "$patch")"
	fi
done

# Codecs are off: GNOME Remote Desktop works with the built-in RemoteFX and
# progressive codecs, and each codec adds several minutes of build time.
cmake -S "$SRC/client/Android/Studio/freeRDPCore/src/main/cpp" \
	-B "$BUILD" \
	-G Ninja \
	--toolchain "$NDK/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI="$ABI" \
	-DANDROID_PLATFORM=android-29 \
	-DCMAKE_BUILD_TYPE=Release \
	-DWITH_OPENSSL=ON \
	-DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_OPUS=OFF \
	-DWITH_PNG=OFF -DWITH_WEBP=OFF -DWITH_JPEG=OFF -DWITH_CJSON=OFF

cmake --build "$BUILD"

# The superbuild installs FreeRDP, OpenSSL and uriparser into the upstream
# freeRDPCore jniLibs folder. OpenSSL's legacy.so provider is skipped: WinPR
# is built with its own MD4/RC4 for NTLM.
UPSTREAM_LIBS="$SRC/client/Android/Studio/freeRDPCore/src/main/jniLibs/$ABI"
mkdir -p "$OUT"
rm -f "$OUT"/*.so
cp "$UPSTREAM_LIBS"/lib*.so "$OUT/"
cp "$BUILD/libfreerdp-android.so" "$OUT/"
ls -1 "$OUT"
