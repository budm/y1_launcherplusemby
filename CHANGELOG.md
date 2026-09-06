# Changelog

All notable changes to this fork (Emby sync + related additions on top of
JJ Launcher) are documented here. Versions are keyed by `versionCode` /
`versionName` from `app/build.gradle`, matching what the in-device System
Update page compares against.

## [900061] - 0.11.6 hotfix 4 (custom: Emby Sync)

### Added
- Emby server sync: connect to a self-hosted Emby server and download the
  music library into `Artist/Album/Track` folders.
- On-device Emby login via the wheel keyboard (host, username, password),
  as an alternative to entering credentials through the Web Server browser
  page.
- Daily automatic sync via `AlarmManager`, with:
  - On/off toggle and adjustable sync hour in Settings.
  - Wi-Fi guard — skips quietly if not connected.
  - Low-battery guard — skips if under 15% and not charging.
  - Pruning — removes local files/manifest entries for tracks deleted
    server-side.
- Non-blocking sync progress: a small corner indicator instead of a
  full-screen overlay, so the device stays usable during sync.
- Scroll acceleration: fast wheel spins jump multiple positions at once,
  both in the on-device keyboard and when browsing songs/artists/albums.
- Screen timeout: added 10 Min, 15 Min, 30 Min, 1 Hour, and Always On to
  the existing options.
- Automatic time & timezone sync (network-based, since this hardware has
  no cell radio/GPS to auto-detect from) — keeps the clock accurate for
  the daily sync schedule.
- Online lyrics lookup (LRCLIB, no API key) as a fallback when no local
  `.lrc` file or embedded lyrics are found, only when on Wi-Fi.
- Self-hosted OTA update checking — points at a GitHub-hosted
  `output-metadata.json` instead of the original author's server, with a
  versionCode bumped well above upstream so this fork's own OTA checker
  never mistakes an original-project release for an update.
- "What's New" changelog display on the in-device System Update page,
  read from the same `output-metadata.json` used for update checking.

### Changed
- `versionCode`/`versionName` bumped and relabeled to distinguish this
  custom build from upstream releases.
