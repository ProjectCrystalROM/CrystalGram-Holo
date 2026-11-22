#!/bin/sh
# OpenSSL 1.1.1w -> Android armv7a / API 16 static libraries.
#
# Notes on why this is a wrapper script rather than upstream's build-openssl.sh:
#  * The NDK r23c compiler wrapper scripts (armv7a-linux-androideabi16-clang)
#    are /bin/bash scripts whose body is `dirname $0`/clang.exe ... . That body
#    breaks whenever the toolchain path contains a space (as it does here), so we
#    never call them. Instead we put our own no-space wrapper named exactly as
#    OpenSSL's Configure expects on PATH, ahead of the NDK, and have it exec the
#    real clang.exe with an explicit --target.
#  * OpenSSL's Configure needs a POSIX sh + perl + make; MSYS2 supplies those.
#    No Linux, no WSL: the NDK toolchain itself is a native Windows clang.
#
# Run from MSYS2 bash:  bash scripts/openssl-build.sh
set -e

ROOT="D:/Appdev/CrystalGram Holo"
NDK="$ROOT/.tools/sdk/ndk/23.2.8568313"
SRC="$ROOT/.tools/openssl-src/openssl-1.1.1w"
OUT="$ROOT/third_party/openssl/23.2.8568313/android-16/armeabi-v7a"
SHIM="$ROOT/.tools/shim"
TOOLBIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"

export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"

# --- no-space compiler shims ------------------------------------------------
mkdir -p "$SHIM"
for name in armv7a-linux-androideabi16-clang armv7a-linux-androideabi16-clang++ \
            armv7a-linux-androideabi16-gcc armv7a-linux-androideabi16-g++ ; do
  cat > "$SHIM/$name" <<EOF
#!/bin/sh
exec "$TOOLBIN/clang.exe" --target=armv7a-linux-androideabi16 "\$@"
EOF
  chmod +x "$SHIM/$name"
done
for name in llvm-ar llvm-ranlib llvm-nm ; do
  printf '#!/bin/sh\nexec "%s/%s.exe" "$@"\n' "$TOOLBIN" "$name" > "$SHIM/$name"
  chmod +x "$SHIM/$name"
done

export PATH="$SHIM:$TOOLBIN:$PATH"

cd "$SRC"
echo "== Configure (android-arm, no-shared, api 16) =="
perl ./Configure android-arm no-shared no-tests no-async \
  -D__ANDROID_API__=16 -D__ARM_MAX_ARCH__=8 \
  --prefix="$OUT" --openssldir="$OUT/ssl" > configure.log 2>&1 || {
    tail -20 configure.log; exit 1; }
grep -E "^CC=|^AR=|^RANLIB=" Makefile

echo "== make -j8 =="
make -j8 > build.log 2>&1 || { tail -30 build.log; exit 1; }

echo "== install_sw =="
make install_sw > install.log 2>&1 || { tail -20 install.log; exit 1; }

ls -la "$OUT/lib"
