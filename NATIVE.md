# Bundled native library

The APK ships one ARMv7 shared library at
`app/src/main/jniLibs/armeabi-v7a/libtdjni.so`. It contains TDLib 1.8.71
(layer 234) at `1b9efe84d6e9a2e2f6eadeeea32b0726d2b8c25d`, static OpenSSL
3.5.9, SQLite, and the static C++ runtime.

## Rebuild

Use JDK 17, a host C/C++ compiler with C++17 support, CMake 3.x, Ninja, gperf,
Perl, Make, Python 3, GNU readelf, and Android NDK r23c (23.2.8568313).
Set `ANDROID_NDK_ROOT` to the installed NDK and `CG_CMAKE` if CMake is outside
your PATH. The NDK's 32-bit API 16 sysroot is required for the target.

Use clean source checkouts at the TDLib commit above and OpenSSL tag
`openssl-3.5.9` (`45e844fa2a14ec92d146bd8f5778ac130b6625fb`).

```bash
scripts/build-openssl-android.sh /path/to/openssl .research/openssl-api16
scripts/build-tdlib-android.sh /path/to/td .research/openssl-api16 .research/tdlib-api16
```

The TDLib script applies the Android certificate-store patch to its source
checkout, generates C++ and Java sources on the host, then cross-builds and
checks the library. To update the app, copy `TdApi.java` from the output into
`app/src/main/java/org/drinkless/tdlib/` and `libtdjni.so` into
`app/src/main/jniLibs/armeabi-v7a/` as a pair. Verify `Client.java` against the
upstream copy in the output. JNI registers methods during `JNI_OnLoad` and
checks the matching generated API revision during startup.

## Compatibility

The native target is ARMv7, VFPv3-D16 with no binary-wide NEON requirement.
`scripts/check-api16-native.py` checks the bundled binary against the NDK API
16 stubs and validates its ELF traits. The five dynamic dependencies are
`liblog.so`, `libdl.so`, `libz.so`, `libm.so` and `libc.so`. The library
retains SysV hashing and conventional ARM relocations for Jelly Bean's linker.

OpenSSL uses Android's system CA store through the patch in
`scripts/patches/tdlib-android-system-certs.patch`. Jelly Bean uses
`/system/etc/security/cacerts`. Newer Android releases can use the Conscrypt
APEX store. Peer verification remains enabled.

The [compatibility audit](COMPATIBILITY-AUDIT.md) records the ELF checks and
the API 18 in-place upgrade result.

## Licensing

TDLib is licensed under the Boost Software License 1.0. The OpenSSL 3.5.9
Apache 2.0 license is included in `app/src/main/assets/licenses/OpenSSL.txt`.
