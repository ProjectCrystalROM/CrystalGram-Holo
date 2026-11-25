# CrystalGram Holo

An unofficial Telegram client for Android 4.1–4.3 (Jelly Bean, API 16–18), built
on Android's own Holo widgets with CrystalGram's own chat bubbles.

CrystalGram Holo is not affiliated with, endorsed by, or produced by Telegram.
It uses the public Telegram API under its own application credentials.

## Status

Developed against a Samsung Galaxy S III (SCH-I535, `d2vzw`) running
CyanogenMod 10.2 — Android 4.3.1, API 18, armeabi-v7a.

| Area | State |
|---|---|
| Dark Holo chrome (platform widgets, no AndroidX/Material) | working |
| CrystalGram bubbles: rounded corners, tail, per-bubble timestamps | working |
| TDLib backend, native bridge on armv7 | working |
| Authentication: phone, code, optional password | working |
| Chat list for private chats and groups, Telegram's own ordering | working |
| Text messaging, send dedupe on server ack | working |
| Message history, paging backwards on scroll | working |
| Read receipts and typing indicators (API ToS §1.4) | working |
| Channels | **out of scope — see below** |
| Photo and sticker previews, video thumbnails, file attachments | supported |
| Full playback, document opening and voice playback | out of scope |
| Background notifications | TDLib connection, no Google Play Services |

The TDLib 1.8.71 upgrade was installed over the previous signed release on
the API 18 device. The existing session resumed and queued notifications
arrived after the upgrade. API 16 was checked statically. See the
[compatibility audit](COMPATIBILITY-AUDIT.md) for details.

## Design rules

Two, and they are not negotiable:

**Chrome belongs to Android.** `Theme.Holo`, platform `ListView` / `EditText` /
`Button` / `ActionBar`. No AndroidX, no Material, no Compose. The point is a
client that looks native to the device it runs on.

**Bubbles belong to CrystalGram.** Rounded rectangles with a tail drawn as a
single `Path` so there is no seam between body and tail:

| Role | Colour |
|---|---|
| Sent | lavender `#D8CDF0`, left strip `#6F5FA8` |
| Received | mint `#CDE8D8`, left strip `#4E8A6C` |
| Bubble text | `#1A1A1A` |
| Timestamp | `#5A5A5A`, 75% size, on its own line, aligned to the bubble's outer edge |

## Why channels are not supported

Telegram's API Terms of Service §3.3 requires that a client which shows channels
renders Telegram's official sponsored messages in them. That obligation cannot
be discharged by showing a channel with its sponsors stripped out.

So channels are excluded from the chat list entirely. This is a scope decision,
not content filtering: there is no channel view for sponsors to be removed
from, and no sponsored message is ever relocated, restyled, hidden inside a
channel, or substituted with other content.

The alternative — implementing sponsors faithfully, including ones we would
rather not carry — is the only other compliant path. That decision is pending
word from Telegram support about whether channel support is optional for
third-party clients.

## Building

Use JDK 17, Gradle 7.6.3, Android SDK Platform 34 and Build Tools 34.0.0.
Create `app/local.properties` with your SDK path and Telegram application
credentials from [Telegram](https://my.telegram.org/apps).

```properties
sdk.dir=/path/to/android-sdk
api_id=12345
api_hash=your_telegram_api_hash
```

Run `gradle -p app assembleDebug lintDebug`. The APK is written to
`app/app/build/outputs/apk/debug/`. The repository includes the native
library. See [NATIVE.md](NATIVE.md) for native rebuild instructions.

For a signed release update, set `keystore.path`, `keystore.password`, and
`keystore.alias` in the same ignored file, then run
`gradle -p app assembleRelease`. Set `keystore.keyPassword` if the key has a
separate password. An in-place install requires the existing app's signer.

## Licence

Application source: to be decided.

The bundled native library in `app/src/main/jniLibs/armeabi-v7a/` is TDLib,
built from upstream source by this project and unrelated to this application's
own code; see [NATIVE.md](NATIVE.md) for how it is built, why the commit is
pinned, and the terms that apply to it.
