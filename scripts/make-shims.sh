#!/bin/sh
# Create no-space compiler shims for the Android NDK (armv7a / API 16).
#
# Why: autoconf's `configure` and the NDK's own compiler wrapper scripts split
# $PATH / `dirname $0` on whitespace, which breaks when the toolchain lives under
# a path containing a space ("D:/Appdev/CrystalGram Holo/..."). The shims live in
# a space-free directory (C:/cgh-tools/shim) and exec the real clang.exe with an
# explicit --target, so both autoconf and plain make work.
#
# Run from MSYS2 bash:  bash scripts/make-shims.sh
set -e

SHIM="${SHIM_DIR:-C:/cgh-tools/shim}"
CLANG="D:/Appdev/CrystalGram Holo/.tools/sdk/ndk/23.2.8568313/toolchains/llvm/prebuilt/windows-x86_64/bin"

mkdir -p "$SHIM"

# C / C++ compilers under every name autoconf or OpenSSL may look for
for name in clang cc gcc c++ g++ clang++ \
            armv7a-linux-androideabi-clang armv7a-linux-androideabi-gcc \
            armv7a-linux-androideabi-cc armv7a-linux-androideabi-c++ \
            armv7a-linux-androideabi-g++ \
            armv7a-linux-androideabi16-clang armv7a-linux-androideabi16-gcc \
            armv7a-linux-androideabi16-clang++ armv7a-linux-androideabi16-g++ ; do
  case "$name" in
    *++) LANG_ARG="-x c++" ;;
    *)   LANG_ARG="" ;;
  esac
  printf '#!/bin/sh\nexec "%s/clang.exe" --target=armv7a-linux-androideabi16 %s "$@"\n' \
    "$CLANG" "$LANG_ARG" > "$SHIM/$name"
  chmod +x "$SHIM/$name"
done

for name in llvm-ar llvm-ranlib llvm-nm llvm-strip ; do
  printf '#!/bin/sh\nexec "%s/%s.exe" "$@"\n' "$CLANG" "$name" > "$SHIM/$name"
  chmod +x "$SHIM/$name"
done

echo "Shims written to $SHIM:"
ls "$SHIM"
"$SHIM/clang" --version | head -2
