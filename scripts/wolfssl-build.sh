#!/bin/sh
# wolfSSL 5.9.4 -> Android armv7a / API 16 static library (libwolfssl.a)
# Built in OpenSSL-compatibility mode so TDLib can compile unmodified against it.
#
# Run from MSYS2 bash:  bash scripts/wolfssl-build.sh
set -e

ROOT="D:/Appdev/CrystalGram Holo"
NDK="$ROOT/.tools/sdk/ndk/23.2.8568313"
SRC="$ROOT/.tools/wolfssl-src/wolfssl-5.9.4-stable"
OUT="$ROOT/third_party/wolfssl/23.2.8568313/android-16/armeabi-v7a"
SHIM="/c/cgh-tools/shim"
TOOLBIN="/d/Appdev/CrystalGram Holo/.tools/sdk/ndk/23.2.8568313/toolchains/llvm/prebuilt/windows-x86_64/bin"
NM="$TOOLBIN/llvm-nm.exe"

export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"
mkdir -p "$SHIM"
export PATH="$SHIM:$TOOLBIN:$PATH"

cd "$SRC"

echo "== configure (armv7a, API 16, openssl-compat) =="
# NOTE: the MSYS2 bash that starts this script carries ac_executable_extensions='.exe'
# (that is why `./configure` cannot see the extension-less shims). Clear it before
# autoconf runs. Use plain `./configure` so it runs under bash like upstream intends.
unset ac_executable_extensions
export ac_executable_extensions=
# autoconf cannot probe `ar` through these shims, so state the tools explicitly.
export AR="$SHIM/llvm-ar"
export RANLIB="$SHIM/llvm-ranlib"
export NM="$SHIM/llvm-nm"
export STRIP="$SHIM/llvm-strip"
./configure \
  --host=armv7a-linux-androideabi \
  --enable-static --disable-shared \
  --enable-opensslextra --enable-opensslall \
  --enable-tls13 --enable-sni --enable-alpn \
  --enable-curve25519 --enable-ed25519 \
  --enable-aesctr --enable-sha512 --enable-harden \
  --disable-threadlocal --enable-armasm=inline \
  --disable-examples --disable-crypttests --disable-benchmark \
  --prefix="$OUT" > configure.log 2>&1 || { tail -30 configure.log; exit 1; }
tail -5 configure.log

echo "== make -j8 =="
make -j8 > build.log 2>&1 || { tail -40 build.log; exit 1; }
# make already produced a complete static archive at src/.libs/libwolfssl.a.
mkdir -p "$OUT/lib" "$OUT/include"
cp -f src/.libs/libwolfssl.a "$OUT/lib/libwolfssl.a"
cp -rf wolfssl "$OUT/include/"

echo "== install =="
make install > install.log 2>&1 || { tail -20 install.log; exit 1; }

ls -la "$OUT/lib"
"$NM" --defined-only "$OUT/lib/libwolfssl.a" 2>/dev/null | grep -cE " (T|t) " | sed 's/^/defined symbols: /'
