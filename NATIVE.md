# Bundled native library

`app/src/main/jniLibs/armeabi-v7a/libtdjni.so` is built by this project from
TDLib source. It is the only `.so` the app ships: OpenSSL and the C++ runtime
are linked into it statically, so there is no `libsslx.so`, `libcryptox.so` or
`libc++_shared.so` to keep in step.

| File | Size | Built from |
|---|---|---|
| `libtdjni.so` | ~17 MB | TDLib `42e6a5259551178d1dab54a22ad96d14bd906e20` |

## The commit must not change

TDLib's `JNI_OnLoad` compares a hash baked into the generated `TdApi.java`
against one baked into the native library and **aborts the process** on a
mismatch. There is no exception to catch and nothing in logcat beyond the
library loading.

`TdApi.java` in this repository was generated from the source tree at exactly
`42e6a5259551178d1dab54a22ad96d14bd906e20`. Regenerating it at a different
commit means rebuilding the library in the same pass — never one without the
other.

## How it is built

Two stages. This is upstream's method (`example/android/build-tdlib.sh`), and
the two-stage split is not optional: TDLib's CMake skips every source-generation
rule when cross-compiling, because the generators are host executables it cannot
run for the target. Skipping stage 1 means the cross build fails partway through
looking for a generated file that nothing will produce.

**Stage 1 — generate sources natively.**

```sh
mkdir build-native && cd build-native
cmake .. -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DTD_GENERATE_SOURCE_FILES=ON \
  -DTD_ENABLE_JNI=ON
cmake --build . -j"$(nproc)"
```

`-DTD_ENABLE_JNI=ON` is the flag that is easy to miss and expensive to debug
without: `td/generate/CMakeLists.txt` only defines `TD_ENABLE_JNI=1` for the
`generate_common` target when it is set, so without it `td_api.h` is emitted
without `get_package_name_ref`, `get_git_commit_hash` or `Function::fetch` —
and `td_jni.cpp` then fails to compile with seven errors about missing members.
The header is regenerated and looks plausible; only those symbols are absent.

**Stage 2 — cross-compile the target.**

```sh
mkdir build && cd build
cmake ../../tdlib-src/example/android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=armeabi-v7a \
  -DANDROID_PLATFORM=android-16 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DOPENSSL_ROOT_DIR="$OPENSSL" \
  -DOPENSSL_USE_STATIC_LIBS=TRUE
cmake --build . --target tdjni -j"$(nproc)"
```

`android-16` matches `minSdkVersion`. TDLib's default is newer, and building
against it produces calls that will not resolve on Jelly Bean. `c++_static`
avoids shipping a second shared library.

`scripts/build-tdlib-android.sh` runs both stages and refuses to start if the
source tree is not at the expected commit.

## Toolchain used

| Component | Version |
|---|---|
| NDK | 23.2.8568313 |
| CMake | 3.31.6 (upstream pins 3.22.1; the SDK here ships neither) |
| OpenSSL | 1.1.1w, `no-asm`, static, `android-16/armeabi-v7a` |
| Generator | Ninja 1.13.2 |

Upstream's `build-openssl.sh` builds OpenSSL 1.1.1 by default for the same
reason this project does: much smaller binary and better performance on this
hardware than 3.x. `no-asm` is required — the ARM assembly paths do not build
under NDK 23 for this target.

## Why it is committed

17 MB of build output in git is real weight. It is committed so that a clone
builds and runs without first reproducing a native toolchain, and because the
alternative — a binary host that can be lost — is worse.

To verify a committed library matches its stated source:

```sh
llvm-nm -D --defined-only libtdjni.so | grep JNI_OnLoad
llvm-readelf -d libtdjni.so | grep NEEDED
```

`NEEDED` should list only `liblog`, `libdl`, `libz`, `libm` and `libc`. A
`libsslx.so`, `libcryptox.so` or `libc++_shared.so` there means the static
link did not take.

There are no `Java_org_drinkless_tdlib_*` symbols in either a correct or an
incorrect build: `td_jni.cpp` registers its native methods dynamically in
`JNI_OnLoad` via `RegisterNatives`. `JNI_OnLoad` being present is the whole
check at the symbol level; whether the version hash agrees can only be proven
on a device.

## Licensing

Upstream TDLib is Apache-2.0. OpenSSL 1.1.1 is under the OpenSSL/SSLeay licence.
No application code from Telegram X or the official Telegram client is included
or was ever compiled in.
