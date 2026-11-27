# OpenUp

**OpenUp** is a free and open source backup app for Android, inspired by Swift Backup.
It uses [Shizuku](https://shizuku.rikka.app/) to get the same privileges as `adb`, so it can back up
and restore much more than a normal app — **without root**.

> OpenUp is an independent project. It is not affiliated with, endorsed by, or based on the code
> of Swift Backup.

## What gets backed up

| Category | Backup | Restore | Notes |
|---|---|---|---|
| **APKs** (incl. split APKs) | ✅ | ✅ | Installed silently through a `pm` install session. Optionally restores the original installer (e.g. Google Play). |
| **External data** (`Android/data/<app>`) | ✅ | ✅ | Read and written by the Shizuku shell process. Cache folders can be skipped. |
| **Expansion files / OBB** (`Android/obb/<app>`) | ✅ | ✅ | |
| **Media** (`Android/media/<app>`) | ✅ | ✅ | |
| **Permissions & app settings** | ✅ | ✅ | Runtime permissions, usage access, accessibility services, battery optimisation (unrestricted/restricted) and other special access (display over other apps, modify system settings, install unknown apps, all files access, alarms, picture-in-picture, media management, full-screen notifications). Notification settings and the SSAID need root. |
| **Batch restore** | ✅ | ✅ | Any number of apps at once, without a confirmation per app. |
| **Messages** (SMS, MMS with attachments, RCS) | ✅ | ✅ | No need to become the default SMS app: OpenUp allows itself to write SMS through Shizuku. RCS chats (stored by Google Messages as MMS) are included, and on Android 17 also end-to-end encrypted RCS chats: Shizuku grants OpenUp the `READ_RESTRICTED_MESSAGES` app-op, so scheduled backups include them without prompts. Duplicates are skipped. |
| **Call logs** | ✅ | ✅ | Duplicates are skipped. Voicemail entries cannot be restored by third-party apps. |
| **Folders** | ✅ | ✅ | Any folder on internal storage or an SD card, including inside `Android/data` and `Android/obb`. Restores merge into the folder (same names are overwritten, other files kept). Folders outside `Android/data`/`obb` also work without Shizuku. |
| **Wallpapers** (home + lock) | ✅ | ✅ | Live wallpapers are recorded by name only. |
| **Wi-Fi networks** (with passwords) | ✅ | ✅ | Uses the Wi-Fi service as the shell user (which may read credentials). Enterprise/EAP networks are skipped. Swift Backup needs root for this. |
| Private app data (`/data/data`) | ❌ | ❌ | Android does not allow this without root — not even for `adb`. |

### Other features

- Batch backup and restore of any number of apps, with a per-part choice (APK, data, OBB, media, permissions).
- Backup versions: keep 1–10 backups per app (or per category); older ones are pruned automatically.
- **Compression** with Zstandard (levels 1–19) or none.
- **Encryption** with a password: AES-256-GCM in authenticated 1 MiB chunks (the STREAM construction), PBKDF2-HMAC-SHA256 (600 000 iterations) + HKDF per file. Tampering, truncation and reordering are detected.
- End-to-end integrity: every backup file stores the SHA-256 of its content, which is verified on restore. APKs are verified before the install session is committed.
- Safe restores: archives are extracted into a staging folder first, so existing data is only replaced after the whole archive was read successfully.
- **Scheduled backups** (WorkManager): apps (all or only new/updated), folders, messages, call logs, wallpapers, Wi-Fi.
- App list with search, filters (user/system/backed up/not backed up/outdated/uninstalled with backup) and sorting.
- App actions: launch, app info, force stop, enable/disable, clear data, uninstall.
- Material 3 UI with dynamic colors and dark theme.
- No internet permission. Nothing ever leaves your device unless you copy the backup folder yourself.

## Download

Get the latest signed APK from the [Releases page](../../releases). Check it against `SHA256SUMS.txt`.

## Getting started

1. Install [Shizuku](https://shizuku.rikka.app/download/) and start it
   (Wireless debugging on Android 11+, or with a computer via `adb`). Shizuku has to be started again after every reboot.
2. Open OpenUp and tap **Grant permission** on the Shizuku card.
3. Complete the **Finish setup** card (storage access, notifications, background activity). With Shizuku
   running, OpenUp grants these itself.
4. Back up apps from **Apps**, or use the other categories on the home screen.

Backups are stored in `/storage/emulated/0/OpenUp` by default (changeable in Settings). To keep backups
safe from losing the phone, copy or sync that folder somewhere else (computer, Syncthing, Nextcloud, …).

## How it works

OpenUp binds a [Shizuku user service](https://github.com/RikkaApps/Shizuku-API#userservice):
a small piece of OpenUp's own code that Shizuku runs in a separate process as the shell user
(uid 2000). That process can access `Android/data` and `Android/obb`, run `pm`/`cmd` commands and
talk to system services with shell permissions. Archives are streamed between the two processes
through pipes, so nothing has to fit in memory or be staged in temporary files:

```
backup:  shell process: walk Android/data/<app> → tar ─pipe→ app: zstd → AES-GCM → file + SHA-256
restore: app: file → AES-GCM → zstd → verify ─pipe→ shell process: tar → staging dir → swap
```

## Backup folder format

```
OpenUp/
  apps/<package>/<yyyyMMdd-HHmmss>/
    meta.json                  app label, version, installer, signing certificates, permissions, file list
    icon.png
    apk/base.apk[.enc]         split_*.apk[.enc] for split APKs
    extdata.tar[.zst][.enc]    Android/data/<package>
    obb.tar[.zst][.enc]        Android/obb/<package>
    media.tar[.zst][.enc]      Android/media/<package>
  messages/<id>/  meta.json, messages.tar[.zst][.enc]   (sms.jsonl, mms.jsonl, parts/*)
  calllogs/<id>/  meta.json, calllogs.jsonl[.zst][.enc]
  wallpapers/<id>/ meta.json, home.img[.enc], lock.img[.enc]
  wifi/<id>/      meta.json, wifi.json[.zst][.enc]
  folders/<name>_<hash>/<id>/ meta.json (original path), folder.tar[.zst][.enc]
```

Unencrypted archives are standard POSIX tar files (compressed with standard zstd), so they can be
inspected with ordinary tools: `zstd -d extdata.tar.zst && tar -tf extdata.tar`.

## Building

Requirements: JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest lintDebug
```

Release builds are minified with R8. To sign them, create `keystore.properties` in the project root
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Limitations

- Private app data (`/data/data/<app>`, databases, shared preferences, logins) cannot be backed up without root.
- Apps installed again from a backup are restored only if they are not installed or older than the backup
  (Android does not allow downgrades).
- Shizuku must be running for app and Wi-Fi backups. Scheduled backups skip those parts while it is not running.
- Restoring messages requires that Android lets OpenUp write SMS (set automatically through Shizuku).
- Restored end-to-end encrypted RCS messages come back as normal messages (without Android 17's "restricted" marker).
- A Google Messages bug (since about May/June 2026) stops it from saving *sent* RCS messages to Android's message
  database; no backup app can see those until Google fixes it.

## License

OpenUp is licensed under the [GNU General Public License v3.0](LICENSE).

Third-party libraries: [Shizuku API](https://github.com/RikkaApps/Shizuku-API) (Apache 2.0),
[zstd-jni](https://github.com/luben/zstd-jni) (BSD), AndroidX and Jetpack Compose (Apache 2.0),
kotlinx.coroutines and kotlinx.serialization (Apache 2.0).
