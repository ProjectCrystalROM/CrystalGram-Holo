# CrystalGram Holo — TDLib on Android 4.1 (API 16) with wolfSSL

Research and build log for a Windows-native Telegram client for Android 4.1–4.3.
Updated as work proceeds.

---

## 1. Telegram X "legacy": what it actually is

Verified from upstream source.

| Question | Finding | Evidence |
|---|---|---|
| Which TGX releases support API 16? | The `legacy` flavor of **every current release**, including `v0.29.2.1832-beta`. | `buildSrc/src/main/kotlin/Config.kt`: `Sdk.VARIANTS[LEGACY] = SdkVariant("legacy", minSdk = 16, maxSdk = 20)` |
| Which TDLib do they use? | **Upstream `tdlib/td` at a pinned commit**, not a fork. | `.gitmodules`: `tdlib → TGX-Android/tdlib`, which is a *prebuilt bundle* whose `version.txt` names the `tdlib/td` commit. |
| Fork or upstream? | Upstream + one debug patch. | `TGX-Android/tdlib/source/patches/client-interceptor.patch` |
| Source + build scripts public? | Yes, `tdlib/td` `example/android/`, whose **default API level is 16**. | `build-tdlib.sh`: `ANDROID_API_LEVEL=${6:-16}` |
| NDK | r23c (`23.2.8568313`) — *"The last release that supports android-16"*. | `version.properties`; NDK r24+ moves the 32-bit floor to 19, r26+ to 21 |
| Rebuildable for ARMv7 / API 16? | Yes, Telegram X does it for every release (`armeabi-v7a`, `android-16`, NDK r23c). | `build-tdlib.sh`: `legacy) ANDROID_API_LEVEL=16; ABIS="armeabi-v7a x86"` |
| Compatible with current servers? | Yes — the legacy build ships with current TDLib (v1.8.71). | |
| Patches needed on newer TDLib? | None. | |

**Conclusion: TDLib is viable; the historical blocker is the toolchain and build
environment, not TDLib itself.**

Pinned revision: `tdlib/td` @ `f10611ee1f03f91ddae8185483a1ccfd3d664668` (v1.8.71).

## 2. Cryptography: wolfSSL 5.9.4 (not OpenSSL)

TDLib is compiled unmodified against **wolfSSL 5.9.4** in OpenSSL-compatibility mode
(`--enable-opensslextra --enable-opensslall`). OpenSSL 1.1.1 (EOL 2023) is not used.

What TDLib needs, measured from the pinned tree:

* **TLS**: `SSL_CTX_new(TLS_client_method())`, `SSL_CTX_set_min_proto_version(TLS1_VERSION)`,
  `SSL_CTX_set_cipher_list("DEFAULT")`, `SSL_CTX_set_verify`,
  `SSL_CTX_load_verify_locations`, `SSL_CTX_set_cert_store`, `SSL_new`, `SSL_set_bio`,
  `SSL_read/write`, `SSL_get_error`, `SSL_shutdown`.
* **X509**: `X509_STORE_new`, `X509_STORE_add_cert`, `X509_STORE_load_locations`,
  `X509_STORE_get0_objects`, `X509_VERIFY_PARAM_set1_host`, `d2i_X509`, `X509_free`, …
* **Crypto**: `EVP_MD_CTX` + SHA-256/512, `EVP_CipherInit_ex` + AES-256-CBC/ECB,
  `EVP_PKEY` X25519 (`new_raw_private_key`, `derive`), RSA PKCS#1 v1.5, `HMAC`,
  `RAND_bytes`, `BIGNUM`.
* TDLib branches on `OPENSSL_VERSION_NUMBER`. wolfSSL reports `0x10001040` (1.0.1u) in
  `OPENSSL_ALL` mode, which selects TDLib's pre-1.1.0 code paths — the ones wolfSSL's
  compatibility layer implements most completely.

**Verified so far** (`scripts/probe-wolfssl.c`): compiles *and links* for
`armv7a-linux-androideabi16` against `libwolfssl.a`, resolving `SSL_CTX_new`,
`TLS_client_method`, `SSL_CTX_set_min_proto_version`, `SSL_CTX_set_cipher_list`,
`SSL_CTX_set_verify`, `X509_STORE_new`, `SSL_CTX_set_cert_store`,
`EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, …)`, `EVP_MD_CTX_new`, `EVP_sha256`,
`RAND_bytes`. Output is ELF32/ARM with only `libc.so`, `libm.so`, `libdl.so` as NEEDED.
**Not yet verified**: `X509_VERIFY_PARAM_set1_host` (certificate hostname checking),
`X509_STORE_get0_objects`, `SSL_set_bio`, `ERR_*`, and the X509_STORE hand-off at runtime.

Fallbacks if a wolfSSL gap proves impractical: mbedTLS (also needs a shim), LibreSSL
(not maintained for this target). OpenSSL 1.1.1 does not come back.

## 2a. Test device

| | |
|---|---|
| Model | Samsung Galaxy S III, SCH-I535 (`d2vzw`), Verizon |
| OS | **CyanogenMod 10.2 — Android 4.3.1, API 18** |
| ABI | `armeabi-v7a` (Cortex-A9, NEON present) |
| adb | `1b2bbe9e`, authorised |

The kernel is derived from Samsung's last Jelly Bean firmware, which is the normal
arrangement for CM10.2 on this device and is **not** a version conflict. The phone
reports `ro.build.version.release=4.3.1` / `ro.build.version.sdk=18`; an earlier note
in this log that called it a 4.1.2 ROM was reading the `ro.build.fingerprint` string,
which CM10.2 leaves pointing at the Samsung 4.1.2 base. Device checks should trust
`ro.build.version.sdk` (18), not the fingerprint.

## 3. Build environment (Windows native, no WSL2)

| Component | Version | Location |
|---|---|---|
| JDK | Temurin 21.0.13.1+2 | `.tools/jdk21/jdk-21.0.13.1+2` |
| Android cmdline-tools | 13114758 | `.tools/sdk/cmdline-tools/latest` |
| Android platform | android-34 (compile only) | `.tools/sdk/platforms/android-34` |
| Build tools | 34.0.0 | `.tools/sdk/build-tools/34.0.0` |
| NDK | **r23c (23.2.8568313)** | `.tools/sdk/ndk/23.2.8568313` |
| CMake | 3.22.1 | `.tools/cmake-3.22.1` |
| MSYS2 (POSIX shell for `configure`/`make`) | 3.6.12 (devkitPro install) | `C:/devkitPro/msys2` |
| wolfSSL | 5.9.4, static, armv7a / API 16 | `third_party/wolfssl/23.2.8568313/android-16/armeabi-v7a` |
| Gradle / AGP | 7.6.4 / 7.4.2 (planned) | |

### Windows obstacles found and solved

1. **NDK r23c's compiler wrappers are `/bin/bash` scripts whose body is
   `` `dirname $0`/clang.exe ``, and they break on any path containing a space.**
   `scripts/make-shims.sh` writes extension-less POSIX shims into a space-free directory
   (`C:/cgh-tools/shim`) that `exec` the real `clang.exe --target=armv7a-linux-androideabi16`.
2. **MSYS2 exports `ac_executable_extensions=.exe`,** so autoconf only looks for
   `*-gcc.exe` and misses the shims. Cleared in the build scripts.
3. **Windows-style paths (`D:/…`) inside `$PATH` are not converted by MSYS2,** so its
   `find_exec` cannot see them. Build scripts use POSIX paths for anything in `$PATH`.
4. **autoconf cannot probe `ar` through the shims** (libtool silently got `AR=false`).
   `AR`/`RANLIB`/`NM`/`STRIP` are set explicitly now.
5. `libwolfssl.a` needs `-lm` at final link (it calls `pow`/`log`).

## 4. Architecture (planned)

```
app/                  native Android UI only — Java, minSdk 16, no AndroidX
tdlib-java/           org.drinkless.tdlib (generated TdApi/Client)
native/               libtdjni.so CMake project, built offline -> jniLibs
third_party/wolfssl/  prebuilt static wolfSSL per ABI
scripts/              Windows-native build scripts
docs/                 this log
```

## 5. Credentials

`local.properties` (`telegram.api_id`, `telegram.api_hash`), git-ignored. Nothing copied
from Telegram X or any other client.

## 6. Status

- [x] Environment installed (JDK, SDK, NDK r23c, CMake, MSYS2)
- [x] TDLib viability established against the current revision
- [x] wolfSSL 5.9.4 built for armv7a/API 16 — `libwolfssl.a`, 3638 symbols, ELF32/ARM
- [x] TDLib-required OpenSSL symbols compile + link against it (first probe)
- [ ] CMake shim that presents wolfSSL as OpenSSL to TDLib's build
- [ ] TDLib source generation + `libtdjni.so`
- [ ] Android project skeleton, auth, chat list, messaging
- [ ] APK, tests, README
