# CrystalGram Holo

**1.0.1 · Prismatic Echo · Visible Spectrum**

*Putting Faces to Familiar Names*

**CrystalGram Holo is an unofficial client that uses the Telegram API and is
part of the Telegram ecosystem.** It runs on Android 4.1–4.3 (Jelly Bean,
API 16–18) and uses Android's native Holo widgets.

CrystalGram Holo is not affiliated with, endorsed by, or produced by Telegram.
Official CrystalGram Holo builds use the project's own Telegram API credentials
and are distributed through [GitHub Releases](https://github.com/ProjectCrystalROM/CrystalGram-Holo/releases).

## Status

Developed against a Samsung Galaxy S III (SCH-I535, `d2vzw`) running
CyanogenMod 10.2 — Android 4.3.1, API 18, armeabi-v7a.

| Area | State |
|---|---|
| Dark Holo chrome (platform widgets, no AndroidX/Material) | working |
| CrystalGram bubbles: rounded corners, tail, per-bubble timestamps | working |
| Profile pictures | chat lists, conversation and forum headers |
| TDLib backend, native bridge on armv7 | working |
| Existing-account sign-in: phone, code, optional password | supported |
| Chat list for private chats and groups, Telegram's own ordering | working |
| Text messaging, send dedupe on server ack | working |
| Message history, paging backwards on scroll | working |
| Supergroup forums | topic list, scoped history, messaging, reads and notifications |
| Presence | online and last-seen in private chats, typing in both directions |
| Outgoing receipts | Sending, Sent and Seen, including per-topic read state |
| Message actions | long-press to edit text, forward, reply, delete or react with standard emoji |
| Chat management | join supported groups, leave, mute, pin and archive |
| Privacy and security | block users, edit common privacy policies and manage active sessions |
| Cloud expiration | per-chat auto-delete and timed or view-once photos and videos |
| Bots and polls | slash commands, inline callback buttons, poll results and voting |
| Broadcast channels and private bot chats | **out of scope — see below** |
| Photo and sticker previews, video thumbnails, file attachments | supported |
| Ordinary attachments | in-app photo, video and audio viewer, documents open through a chosen Android app |
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

Forum-enabled supergroups open a Holo topic list. For API behavior, topic
routing and the channel exclusion checks, see [Forum topics](docs/forum-topics.md).

## Broadcast channels and private bot chats

Telegram's API Terms of Service §3.3 requires that a client which shows channels
renders Telegram's official sponsored messages in them. That obligation cannot
be discharged by showing a channel with its sponsors stripped out.

So channels are excluded from the chat list entirely. This is a scope decision,
not content filtering: there is no channel view for sponsors to be removed
from, and no sponsored message is ever relocated, restyled, hidden inside a
channel, or substituted with other content.

Broadcast channels are permanently outside the application scope. Forum topic
support applies only to eligible supergroups.

Private bot chats are also excluded from conversation lists, notifications,
navigation, and forwarding destinations. Bot interaction inside supported
groups and voting in polls remain available.

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

CrystalGram Holo's own code is licensed under the [GNU General Public License,
version 3](LICENSE) (GPL-3.0-only). Third-party components retain their own
licences. See [NATIVE.md](NATIVE.md#licensing) for TDLib and OpenSSL.

Each GitHub Release with an APK should make its corresponding source available
from the same release page at no extra charge. Include the GPLv3 text, the
exact app source, and the scripts, source, and patches needed to rebuild the
bundled native library. The automatic GitHub source archive covers the tagged
repository. If the archive lacks any required native sources, attach a separate
corresponding-source archive or link directly to the matching source from the
release notes.
