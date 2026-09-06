# Release Keystore Setup

This project signs release builds with a dedicated keystore instead of the
ambient Android debug keystore, so OTA updates keep working regardless of
which machine or Android Studio install you build from.

**Two files you were given separately — neither goes in this repo:**
- `y1-emby-sync-release.keystore` — the actual signing key
- `keystore.properties` — the password/alias that unlocks it

Both are already covered by `.gitignore` (`*.keystore`, `*.jks`,
`keystore.properties`). Never commit either one. Whoever holds this
keystore can sign an APK that silently installs itself via root on your
device — treat the password like any other credential (a password manager,
not a note pinned to the repo).

## One-time setup

1. Place `y1-emby-sync-release.keystore` at the **project root** — the same
   folder as `settings.gradle`, next to this file.
2. Place `keystore.properties` in that same root folder. It should look like:
   ```properties
   storeFile=y1-emby-sync-release.keystore
   storePassword=<the password you were given>
   keyAlias=y1release
   keyPassword=<the same password>
   ```
3. Build the **release** variant (not debug) — `./gradlew assembleRelease`,
   or select "release" in Android Studio's Build Variants panel. That's the
   build whose output should go out as an OTA update from now on.

## Why this matters

Android only allows an APK to silently replace an already-installed app
(what the in-device System Update flow does via root) if the new APK is
signed with the *same* certificate as what's currently installed. The debug
keystore Android Studio uses by default lives at `~/.android/debug.keystore`
and can silently change (new machine, reinstalled Android Studio, wiped
`~/.android` folder) — and if it does, the *next* OTA update fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, with no explanation shown on-device.

This keystore is a fixed file rather than something tied to your dev
environment, so as long as you keep it (and back it up somewhere safe),
signing stays consistent forever, independent of what machine you build on.

## If you ever lose this keystore

There's no recovery — a lost or corrupted release keystore means every
future build will have a different signature, and OTA updates will
permanently stop working until every device manually reinstalls a build
signed with the new key. Back up both files somewhere durable (a password
manager's file storage, an encrypted drive, etc.) as soon as you receive
them.
