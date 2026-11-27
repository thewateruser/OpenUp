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

Backups are stored in `/storage/emulated/0/OpenUp` by default. To keep them off the phone, open
**Settings → Backup location → Back up to a USB drive** and pick the drive in the system folder picker
(USB drives are only reachable through that picker on Android). Backups then go into an `OpenUp` folder
on the drive. Files larger than 2 GB are split into parts, so FAT32 formatted drives work too. If the drive
is not plugged in, backups stop with a clear message and scheduled backups skip that run.

## Your backups, explained

Nothing about your backups is hidden: they are ordinary files in a folder you choose, in standard formats
you can open on a computer.

### What happens when you back up an app

1. OpenUp creates a new folder for this backup, named after the date and time (UTC), for example
   `20261127-143000`. While the backup runs, the folder is called `20261127-143000.partial`.
2. The **APK** (and any split APKs) are copied as they are. They are already compressed, so OpenUp does
   not compress them again.
3. The app's folders on shared storage — `Android/data`, `Android/obb` and `Android/media` — are each packed
   into one archive. Shizuku lets OpenUp read these folders, which normal apps cannot. Cache folders are
   skipped by default.
4. Each archive is compressed (Zstandard) and, if you set a password, encrypted.
5. OpenUp writes `meta.json` with everything it needs to restore: app name, version, which store installed
   it, the app's signing certificates, granted permissions, app settings (usage access, accessibility,
   battery and other special access) and the list of files with their sizes and SHA-256 checksums.
6. Only when everything was written successfully is the folder renamed from `.partial` to its final
   name. A backup that was interrupted (phone off, drive unplugged) is never mistaken for a good one.
7. Older backups of the same app are deleted, keeping the number you chose in Settings (2 by default).

### What the files look like

```
OpenUp/
├── apps/
│   └── com.example.notes/                 one folder per app
│       └── 20261127-143000/               one folder per backup
│           ├── meta.json                  description of the backup (plain text)
│           ├── meta.sig                   signature of meta.json (only with a backup password)
│           ├── icon.png                   app icon, shown for apps that are not installed
│           ├── apk/
│           │   ├── base.apk               the app itself
│           │   └── split_config.arm64_v8a.apk
│           ├── extdata.tar.zst            Android/data/com.example.notes
│           ├── obb.tar.zst                Android/obb/com.example.notes (games' extra files)
│           └── media.tar.zst              Android/media/com.example.notes
├── messages/20261127-143500/   meta.json, messages.tar.zst   (sms.jsonl, mms.jsonl, parts/…)
├── calllogs/20261127-143500/   meta.json, calllogs.jsonl.zst
├── wallpapers/20261127-143500/ meta.json, home.img, lock.img
├── wifi/20261127-143500/       meta.json, wifi.json.zst
└── folders/Documents_1a2b3c4d5e/20261127-143500/  meta.json, folder.tar.zst
```

- `.tar` is a normal tar archive, `.zst` means it is compressed with Zstandard, and `.enc` is added to every
  file that is encrypted (for example `extdata.tar.zst.enc`, `base.apk.enc`).
- Messages and call logs are stored as JSON, one message or call per line; MMS pictures and other
  attachments are stored as separate files inside the archive.
- Wallpapers are the original image files. Wi-Fi networks are JSON including their passwords, so set a
  backup password if you back them up.
- Files larger than 2 GB are split into `name`, `name.part2`, `name.part3`, … so they fit on USB drives
  formatted as FAT32.
- `meta.json` and `icon.png` are never encrypted, so OpenUp can list your backups without the password.
  They show which apps you backed up and their permissions, but none of their data. With a password,
  `meta.json` is signed (`meta.sig`), so a backup whose description was changed is refused on restore.

### What happens when you restore an app

1. **App:** if the app is missing or older than the backup, the APK is installed silently (no confirmation
   per app). Its checksum is verified before the installation is finished. A newer or identical installed
   version is left alone — Android does not allow downgrades. If the installed app is signed by a
   different developer than the backed-up one, its data, permissions and settings are not restored.
2. Before its data is restored, the app is stopped so it cannot write to its folders at the same time.
3. **Data:** each archive is read, decrypted, decompressed and unpacked into a temporary folder inside the
   app's folder. Only after the whole archive was read and its checksum matched is the app's current data
   replaced with it (files that were not in the backup are removed, so the folder matches the backup).
   The old files are set aside during the swap and put back if it fails, so if anything goes wrong
   (wrong password, damaged file, full storage) the current data stays as it was.
4. **Permissions and settings:** runtime permissions are granted again, and usage access, accessibility
   services, battery optimisation and the other special access are set back to how they were. Only the
   app's own accessibility services are switched on.

Other categories:

- **Messages** and **call logs** are added to what is already on the phone. Messages and calls that already
  exist are skipped, so restoring twice does not create duplicates, and nothing is deleted.
- **Wallpapers** replace the current home and lock screen wallpapers.
- **Wi-Fi networks** are added (or updated if a network with the same name already exists).
- **Folders** are merged back into their original location: files from the backup overwrite files with the
  same name, and other files in the folder are kept.

### Passwords and encryption

With a backup password, every backup file except `meta.json` and `icon.png` is encrypted with AES-256-GCM
(authenticated encryption that detects any change to the file), and `meta.json` is signed with a key
derived from the password. Without a password nothing can be verified, so OpenUp asks before backing up
messages, call logs or Wi-Fi passwords unencrypted. Encrypted backups made before version 1.1.2 have no
signature; they are only restored if you turn on **Settings → Restore backups from older versions**. The key is derived from your password,
which is stored on the phone in Android's secure key store so scheduled backups can run. If you forget the
password, encrypted backups cannot be restored by anyone — there is no recovery.

### Opening a backup without OpenUp

Unencrypted backups only need standard tools on a computer:

```bash
zstd -d extdata.tar.zst
```

```bash
tar -xf extdata.tar
```

For a split file, join the parts first (`cat file file.part2 file.part3 > joined`). Encrypted files can only
be read by OpenUp; their format is documented in
[`StreamCrypto.kt`](app/src/main/java/org/openup/core/crypto/StreamCrypto.kt).

### Technical details

OpenUp binds a [Shizuku user service](https://github.com/RikkaApps/Shizuku-API#userservice):
a small piece of OpenUp's own code that Shizuku runs in a separate process as the shell user
(uid 2000). That process can access `Android/data` and `Android/obb`, run `pm`/`cmd` commands and
talk to system services with shell permissions. Archives are streamed between the two processes
through pipes, so nothing has to fit in memory or be staged in temporary files:

```
backup:  shell process: walk Android/data/<app> → tar ─pipe→ app: zstd → AES-GCM → file + SHA-256
restore: app: file → AES-GCM → zstd → verify ─pipe→ shell process: tar → staging dir → swap
```

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
