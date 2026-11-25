#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 ]]; then
    echo "Usage: $0 <TDLib checkout> <OpenSSL API 16 install prefix> <output directory>" >&2
    exit 2
fi

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_dir=$(realpath "$1")
openssl=$(realpath "$2")
output_dir=$(realpath -m "$3")
cmake="${CG_CMAKE:-cmake}"
ndk="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$root/toolchain/sdk}/ndk/23.2.8568313}"
jobs="${CG_JOBS:-4}"
revision=$(git -C "$source_dir" rev-parse HEAD)
example="$source_dir/example/android"
host="$output_dir/host"
arm="$output_dir/arm"
patch="$root/scripts/patches/tdlib-android-system-certs.patch"

if git -C "$source_dir" apply --check "$patch" 2>/dev/null; then
    git -C "$source_dir" apply "$patch"
elif ! git -C "$source_dir" apply --reverse --check "$patch"; then
    echo "Android certificate patch does not apply to $revision" >&2
    exit 1
fi

# A Git worktree requires an explicit revision because older CMake Git
# helpers can discover the primary checkout's HEAD.
"$cmake" -S "$example" -B "$host" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DTD_GIT_COMMIT_HASH="$revision" -DTD_GENERATE_SOURCE_FILES=ON \
    -DPHP_EXECUTABLE=OFF
"$cmake" --build "$host" --target tl_generate_tlo -j "$jobs"
"$cmake" --build "$host" --target tl_generate_java tl_generate_common tl_generate_mtproto -j "$jobs"
"$cmake" -S "$example" -B "$host" -DTD_GENERATE_SOURCE_FILES=OFF
"$cmake" --build "$host" --target tdmime_auto -j "$jobs"

"$cmake" -S "$example" -B "$arm" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DTD_GIT_COMMIT_HASH="$revision" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_PLATFORM=android-16 -DANDROID_ABI=armeabi-v7a \
    -DANDROID_ARM_NEON=FALSE -DANDROID_STL=c++_static \
    -U 'OPENSSL_*' -U '_OPENSSL_*' \
    -DOPENSSL_ROOT_DIR="$openssl" -DOPENSSL_USE_STATIC_LIBS=TRUE
"$cmake" --build "$arm" --target tdjni -j "$jobs"

stubs="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/16"
python3 "$root/scripts/check-api16-native.py" "$arm/libtdjni.so" "$stubs"
cp "$arm/libtdjni.so" "$example/org/drinkless/tdlib/TdApi.java" \
    "$source_dir/example/java/org/drinkless/tdlib/Client.java" "$output_dir/"
