# Bundled native libraries

`app/src/main/jniLibs/armeabi-v7a/` contains three `.so` files that this project
did not compile itself. They must ship together: the Java classes and the
native library check each other's version at load time.

| File | Size | Version |
|---|---|---|
| `libtdjni.so` | ~15 MB | TDLib 1.8.67 |
| `libsslx.so` | ~584 KB | OpenSSL |
| `libcryptox.so` | ~2.8 MB | OpenSSL |

## Where they came from

Extracted from the last public Telegram X release that still supports API 16.
TDLib 1.8.67 was confirmed by asking the loaded library for its version at
runtime rather than by guessing.

The corresponding TDLib commit is:

```
42e6a5259551178d1dab54a22ad96d14bd906e20
```

That commit matters. TDLib's `JNI_OnLoad` compares a hash baked into the
generated `TdApi.java` against one baked into the native library, and **aborts
the process** on a mismatch. There is no exception to catch. `TdApi.java` in
this repository was therefore generated from the source tree at exactly that
commit, so the two hashes agree on their own terms. Do not regenerate
`TdApi.java` from a different TDLib version unless you also rebuild the
libraries.

Upstream TDLib is Apache-2.0. OpenSSL 1.1.1 is under the OpenSSL/SSLeay licence.
Telegram X's own application code is not included — only the TDLib build
products.

## Why they are committed

Two reasons, both temporary:

1. They are currently the only copy. Rebuilding TDLib for armv7 against this
   toolchain has not been done yet, so losing these files would set the project
   back significantly.
2. A clone should build and run without first reproducing a TDLib build.

15 MB of someone else's build artifact in git is real weight, and this is
intended to go away. The plan is to replace them with libraries this project
builds from `42e6a525`, at which point they can be deleted and the build
documented instead.

## Rebuilding instead

The intended path is a cross-compile of TDLib for `armeabi-v7a` at API 16 with
OpenSSL 1.1.1w built `no-asm` and static. The stub generator steps
(`generate_common`, `generate_mtproto`, `generate_c`, `generate_json`) must run
on the host, because TDLib's CMake skips them when cross-compiling. See
`scripts/` for the OpenSSL side of that work.
