#!/bin/sh
# On-device TLS validation of the wolfSSL build that will back TDLib.
#
# This is a *native* test binary pushed to the phone, not an APK: it exercises
# exactly the wolfSSL API surface TDLib's SslCtx.cpp / SslStream.cpp use, plus
# the negative cases (expired / wrong-host / self-signed / untrusted root).
#
# Build:  bash scripts/tlsprobe-build.sh   (from MSYS2 bash)
# Run:    adb push tlsprobe /data/local/tmp/ && adb shell /data/local/tmp/tlsprobe
#
# Exit status: 0 only if every expected outcome matched.
set -e

ROOT="D:/Appdev/CrystalGram Holo"
SHIM="/c/cgh-tools/shim"
NDK_TOOLS="/d/Appdev/CrystalGram Holo/.tools/sdk/ndk/23.2.8568313/toolchains/llvm/prebuilt/windows-x86_64/bin"
export PATH="$SHIM:$NDK_TOOLS:$PATH"

cd "$ROOT"
armv7a-linux-androideabi-gcc \
  -DWOLFSSL_USE_OPTIONS_H \
  -I"third_party/wolfssl/23.2.8568313/android-16/armeabi-v7a/include" \
  scripts/tlsprobe.c \
  "third_party/wolfssl/23.2.8568313/android-16/armeabi-v7a/lib/libwolfssl.a" \
  -lm -static -Wl,-z,max-page-size=4096 \
  -o "C:/cgh-tools/tlsprobe"
echo "built C:/cgh-tools/tlsprobe"
