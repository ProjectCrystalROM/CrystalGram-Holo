#!/usr/bin/env bash
# CrystalGram Holo — Linux/WSL2 toolchain environment.
# Source it:  . scripts/env.sh
#
# Everything here comes from apt or from an official Google archive; nothing is
# copied from the old Windows tree except the NDK (r23c is deliberately pinned —
# it is the last NDK that can target API 16).
#
# NOTE: this file is written for the real Linux shell. Do not paste it through
# a Windows->WSL command line, where `$VAR` and backticks get expanded before
# bash ever sees them.

export CRYSTALGRAM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$CRYSTALGRAM_ROOT/toolchain/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/23.2.8568313"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

# JDK 17 from apt (openjdk-21-jdk-headless)
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export NDK_BIN

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$NDK_BIN:$PATH"

# Target: armeabi-v7a / API 16 is the primary (and for now only) ABI.
export CG_ABI=armeabi-v7a
export CG_API=16
export CG_TRIPLE="armv7a-linux-androideabi${CG_API}"
export CC="$NDK_BIN/${CG_TRIPLE}-clang"
export CXX="$NDK_BIN/${CG_TRIPLE}-clang++"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export NM="$NDK_BIN/llvm-nm"
export STRIP="$NDK_BIN/llvm-strip"

# adb over the network; empty CG_DEVICE lets adb pick the only attached device.
export CG_DEVICE="${CG_DEVICE:-}"
