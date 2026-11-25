# CrystalGram Holo compatibility audit

## Starting point

The original audit workspace was a source export. The changes were integrated into the application repository from its existing upstream history. The nested TDLib repository was clean at detached revision `42e6a5259551178d1dab54a22ad96d14bd906e20`. The original packaged JNI binary matched the existing local CMake output byte for byte and contained that revision and TDLib 1.8.67. The original schema used MTProto layer 229. The existing backup libraries and primary TDLib checkout were preserved.

The app uses Gradle 7.6.3 and AGP 7.4.2, compile and target SDK 34, build tools 34.0.0, and min SDK 16. Activities build their views with framework widgets under `Theme.Holo`. There are no external Java runtime dependencies or Google Play Services. D8 failed under JDK 21 and passed under JDK 17. `scripts/env.sh` now selects JDK 17.

One Java `Client` dispatches TDLib results on a receiver thread. The bridge is the upstream registered JNI API with schema-generated Java and C++ classes, compiled into a single `armeabi-v7a` shared object. TDLib owns MTProto sockets, encrypted transport, its binlog, SQLite, and media file storage. The Android client supplies its existing private `getDir("tdlib", MODE_PRIVATE)` path and database settings, caches basic chat/user objects in memory, and renders TDLib updates through main-looper callbacks. The foreground notification service keeps the same TDLib client connected without push infrastructure.

## Android changes

| Files | Changes |
| --- | --- |
| `Td.java`, `LoginActivity.java`, `NetworkMonitor.java` | Moved TDLib initialization and authorization handling out of the activity lifecycle. Configuration is sent once, auth state is replayed to a new activity, failed credentials can be retried, and phone, code, password and email challenges use the appropriate inputs. Logout resets the process client when TDLib closes. The network receiver stays active across screens and while the notification service runs. |
| `MainActivity.java`, `ChatActivity.java` | Wired chat-list updates and removed misplaced chats. Replaced reliance on `Long.compare` with an API-16-safe comparison. The conversation merges overlapping history pages in message order, preserves the scroll anchor, handles edits, deletions and temporary-ID replacement, scopes typing events to the matching chat, marks visible messages read, and pairs open/close and presence updates with lifecycle callbacks. |
| `MediaLoader.java`, `MediaVm.java`, `UploadPrep.java`, `UploadCleanup.java` | Deduplicated preview requests and bounded decode work and memory. Already-local files can be decoded directly, and videos without a thumbnail keep their video label without downloading the full video as an image preview. File preparation runs off the UI thread, uses uniquely named private staging files, retains Unicode names, closes resources, and cleans completed uploads by chat ID plus temporary message ID. Removed the per-chat sweep that could delete an in-flight or resumable upload. |
| `NotificationService.java`, `DismissReceiver.java`, `AndroidManifest.xml` | Kept notification data scoped to the originating TDLib group, isolated message notification IDs from the foreground service, registered the dismissal receiver, and restored service startup after system restart. API 26 channels, API 23 immutable pending intents and API 33 permissions are guarded. API 16 retains framework notifications and the started foreground service. Notification launches use a private activity alias. |

## TDLib and native dependencies

The updated app uses TDLib 1.8.71 at `1b9efe84d6e9a2e2f6eadeeea32b0726d2b8c25d`, the upstream master revision retrieved on November 24, 2026. The 648 intervening commits advance the MTProto layer to 234 and include authorization, synchronization, and transfer fixes. The matching generated `TdApi.java` and `libtdjni.so` ship as a pair. Upstream's `Client.java` and JNI entry points match the existing Java integration. The Android app retains its original package, database directory, encryption-key input, and message-database settings. The database source comparison found no SQLite or binlog revision bump in `Version.h`. Runtime migration still needs validation with existing data.

- TDLib still requests C++17. The upstream Android sample already defaults to API 16 and NDK r23c for its 32-bit build. NDK r24 dropped Jelly Bean sysroots, which is a toolchain support limit. Host tools can be current. CMake, Ninja, the TL generators, and gperf run on the host while Clang cross-compiles against the API 16 ARM sysroot.
- The target stays ARMv7 with VFPv3-D16 and no binary-wide NEON requirement. Platform dependencies are `liblog.so`, `libdl.so`, `libz.so`, `libm.so`, and `libc.so`. SQLite, the C++ runtime and OpenSSL are linked statically. OpenSSL was rebuilt for API 16 and upgraded from 1.1.1w to 3.5.9 LTS. The linker emits the SysV hash and ordinary ARM relocations. TDLib and the runtime use available pthread and clock APIs and emulated TLS.
- The Android trust-store patch is isolated in `scripts/patches/tdlib-android-system-certs.patch`. The original OpenSSL defaults pointed to a build-host directory. The patch reads Android's system CA directory, preferring the Conscrypt APEX store when present and falling back to the Jelly Bean path. Peer verification is retained. Unavailable or expired trusted roots can still cause HTTPS failures on a device. The native system store loads platform roots and does not import user-added roots.
- The optional weak references are handled by the caller. OpenSSL checks `getentropy` before invoking it and can fall back to kernel randomness through its supported paths, including `/dev/urandom`. Clang checks the optional constant-initialized emulated TLS initializer for JNI parse state. The checker reports either symbol explicitly if absent from API 16.
- The existing JNI receive loop releases local references per returned event and clears corresponding Java array slots. Native registration and generated types are shipped from the same schema. The app reports library startup errors and logs exceptions from TDLib result handlers. JNI execution on Dalvik requires device validation.

### Rebuild

Use a dedicated checkout of TDLib `1b9efe84d6e9a2e2f6eadeeea32b0726d2b8c25d` and a clean OpenSSL checkout at tag `openssl-3.5.9` (`45e844fa2a14ec92d146bd8f5778ac130b6625fb`). Build paths can be outside the project. The TDLib script applies the isolated patch in its checkout, generates matching bindings without AndroidX annotations, and verifies the native result before staging it.

```bash
. ./scripts/env.sh
scripts/build-openssl-android.sh /path/to/openssl out/openssl-android-16
scripts/build-tdlib-android.sh /path/to/td out/openssl-android-16 out/tdlib-api16
```

Copy `out/tdlib-api16/TdApi.java` into `app/src/main/java/org/drinkless/tdlib/` and `out/tdlib-api16/libtdjni.so` into `app/src/main/jniLibs/armeabi-v7a/` as a pair. Use Gradle from `app/` with `assembleDebug assembleRelease lintDebug lintRelease`. The staged OpenSSL license is under `app/src/main/assets/licenses/`.

## Build and static validation

- JDK 17 built both APKs and ran both Android Lint configurations. Each lint run reported 34 warnings, zero errors, and zero `NewApi` or `InlinedApi` findings. The project has no Gradle unit-test sources. Remaining lint warnings include hardcoded UI text, locale formatting, and activity references managed by explicit lifecycle callbacks.
- Both APKs declare min SDK 16 and target SDK 34, contain DEX 035 and a single ARMv7 JNI library, pass ZIP alignment, and verify with the API-16-compatible v1 signature. The new release certificate fingerprint matches the pre-audit release APK.
- The JNI payload in each APK matches the audited binary. It declares API 16 in its Android ELF note and exports `JNI_OnLoad`. The NDK API 16 stubs resolve all 208 required dynamic imports. The two remaining weak imports have the guarded paths described above. The binary contains TDLib 1.8.71, the full pinned commit, and OpenSSL 3.5.9.
## Device validation

The signed release installed in place on a Samsung Galaxy S III (SCH-I535)
running Android 4.3.1, API 18. The project maintainer confirmed that the existing
Telegram session resumed and queued notifications arrived after the update, and
accepted the build after device testing. Fresh login and migration on a separate
API 16 device or emulator remain unverified.

The app still uses Jelly Bean's system font repertoire. New emoji sequences can show missing glyphs or separate code points. This checkout already omitted channel browsing and secret-chat setup. Full media playback/document opening, QR sign-in, premium-purchase sign-in and FCM-specific code delivery were outside the original client implementation. The app still uses its built-in media previews and supports Telegram code delivery flows without Google Play Services. Existing failed or interrupted uploads are retained in the app cache, which Android can reclaim.


## Upstream references

[TDLib source revision](https://github.com/tdlib/td/commit/1b9efe84d6e9a2e2f6eadeeea32b0726d2b8c25d) · [NDK r24 requirements](https://github.com/android/ndk/wiki/Changelog-r24) · [Android linker compatibility](https://android.googlesource.com/platform/bionic/+/HEAD/android-changes-for-ndk-developers.md) · [Android system CA store](https://android.googlesource.com/platform/external/conscrypt/+/refs/heads/main/platform/src/main/java/org/conscrypt/TrustedCertificateStore.java) · [OpenSSL 3.5.9](https://github.com/openssl/openssl/releases/tag/openssl-3.5.9)
