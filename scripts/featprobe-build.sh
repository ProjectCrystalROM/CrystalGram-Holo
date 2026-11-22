#!/bin/sh
# Build featprobe for the S3 (armv7a / API 16), linked against our wolfSSL.
# Run from MSYS2 bash:  bash scripts/featprobe-build.sh
set -e

ROOT="D:/Appdev/CrystalGram Holo"
SHIM="/c/cgh-tools/shim"
NDK_TOOLS="/d/Appdev/CrystalGram Holo/.tools/sdk/ndk/23.2.8568313/toolchains/llvm/prebuilt/windows-x86_64/bin"
WS="$ROOT/third_party/wolfssl/23.2.8568313/android-16/armeabi-v7a"
export PATH="$SHIM:$NDK_TOOLS:$PATH"

cd "$ROOT"
# -mfpu=neon so the compiler will accept the NEON test instructions at all;
# the point of the probe is to find out whether the *device* runs them.
armv7a-linux-androideabi-gcc \
  -march=armv7-a -mfpu=neon-vfpv4 -marm \
  -DWOLFSSL_USE_OPTIONS_H \
  -I"$WS/include" \
  scripts/featprobe.c \
  "$WS/lib/libwolfssl.a" \
  -lm -o "C:/cgh-tools/featprobe"
echo "built C:/cgh-tools/featprobe"
