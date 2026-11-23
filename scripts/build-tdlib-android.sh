#!/usr/bin/env bash
#
# Build libtdjni.so from TDLib source, the way upstream intends: two stages.
#
# Stage 1 configures TDLib natively with TD_GENERATE_SOURCE_FILES=ON. That pass
# is not a real build - it exists only to run the generators, which cannot
# execute during a cross-compile. Doing it this way produces every generated
# artifact at once (tl-derived headers, MIME tables, Java API) instead of
# hand-generating them one at a time and discovering the next missing file
# partway through stage 2.
#
# Stage 2 cross-compiles tdjni against those generated sources.
#
# See NATIVE.md for why the commit must not change: JNI_OnLoad hashes the
# generated TdApi and aborts the process on a mismatch, so a different revision
# than the app's TdApi.java is not a build error - it is an uncatchable crash.
#
# Usage:
#   ./scripts/build-tdlib-android.sh
#
# Environment (defaults shown):
#   TD_SRC=$HOME/crystalgram/tdlib-src
#   NDK=$ANDROID_SDK_ROOT/ndk/23.2.8568313
#   OPENSSL=$HOME/crystalgram/toolchain/openssl/android-16/armeabi-v7a
#   OUT=$HOME/crystalgram/out/tdlib-android
#   CMAKE=cmake          (set to a 3.x path if your system cmake is 4.x)
#
set -euo pipefail

TD=${TD_SRC:-$HOME/crystalgram/tdlib-src}
OUT=${OUT:-$HOME/crystalgram/out/tdlib-android}
NDK=${NDK:-${ANDROID_SDK_ROOT:-$HOME/crystalgram/toolchain/sdk}/ndk/23.2.8568313}
OPENSSL=${OPENSSL:-$HOME/crystalgram/toolchain/openssl/android-16/armeabi-v7a}
CMAKE=${CMAKE:-cmake}
NATIVE=$OUT/build-native
CROSS=$OUT/build
WANT=42e6a5259551178d1dab54a22ad96d14bd906e20
JOBS=${JOBS:-$(nproc)}

cd "$TD"
GOT=$(git rev-parse HEAD)
[ "$GOT" = "$WANT" ] || {
  echo "FAIL: TDLib is at $GOT, expected $WANT" >&2
  echo "      TdApi.java in the app was generated at $WANT; a different" >&2
  echo "      revision will fail JNI_OnLoad at startup." >&2
  exit 1
}
echo "commit OK: $WANT"

[ -f "$OPENSSL/lib/libssl.a" ] || { echo "FAIL: no static OpenSSL at $OPENSSL" >&2; exit 1; }
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "FAIL: no NDK at $NDK" >&2; exit 1; }

# --- stage 1: generate sources natively --------------------------------
echo
echo "=== stage 1: generating TDLib sources (native) ==="
mkdir -p "$NATIVE"
cd "$NATIVE"
# TD_ENABLE_JNI must be set here too. Without it generate_common emits
# td_api.h without get_package_name_ref / get_git_commit_hash /
# Function::fetch, and td_jni.cpp fails to compile in stage 2 with seven
# "no member named" errors.
"$CMAKE" "$TD" \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DTD_GENERATE_SOURCE_FILES=ON \
  -DTD_ENABLE_JNI=ON
"$CMAKE" --build . -- -j"$JOBS"

[ -f "$TD/td/generate/auto/td/telegram/e2e_api.h" ] || {
  echo "FAIL: stage 1 did not generate e2e_api.h" >&2; exit 1; }

# --- stage 2: cross-compile --------------------------------------------
echo
echo "=== stage 2: cross-compiling tdjni for armeabi-v7a / android-16 ==="
mkdir -p "$CROSS"
cd "$CROSS"
"$CMAKE" "$TD/example/android" \
  -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=armeabi-v7a \
  -DANDROID_PLATFORM=android-16 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DOPENSSL_ROOT_DIR="$OPENSSL" \
  -DOPENSSL_INCLUDE_DIR="$OPENSSL/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL/lib/libcrypto.a" \
  -DOPENSSL_SSL_LIBRARY="$OPENSSL/lib/libssl.a" \
  -DOPENSSL_USE_STATIC_LIBS=TRUE
"$CMAKE" --build . --target tdjni -- -j"$JOBS"

echo
echo "=== result ==="
find "$CROSS" -name 'libtdjni.so' -exec ls -lh {} \;
echo
echo "Verify the static link took (NEEDED should list only liblog, libdl,"
echo "libz, libm, libc):"
echo "  llvm-readelf -d $CROSS/libtdjni.so | grep NEEDED"
