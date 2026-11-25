#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
    echo "Usage: $0 <OpenSSL 3.5 checkout> <output prefix>" >&2
    exit 2
fi

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_dir=$(realpath "$1")
prefix=$(realpath -m "$2")
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$repo/toolchain/sdk}/ndk/23.2.8568313}"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
mkdir -p "$prefix/build"
cd "$prefix/build"
"$source_dir/Configure" android-arm no-shared no-tests --prefix="$prefix" \
    --openssldir=/system/etc/security -D__ANDROID_API__=16 -march=armv7-a -mfpu=vfpv3-d16
make -j"${CG_JOBS:-4}" build_libs
make install_dev
