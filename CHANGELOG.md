# Changelog

All notable changes to this fork (Emby sync + related additions on top of
JJ Launcher) are documented here. Versions are keyed by `versionCode` /
`versionName` from `app/build.gradle`, matching what the in-device System
Update page compares against.

## [900062] - 0.11.6 hotfix 4 (custom: Emby Sync r2)

### Fixed
- Screen timeout preference wasn't actually persisting across reboots — the
  saved and loaded settings keys didn't match. Also now reapplies the
  OS-level timeout explicitly at boot as a safety net.
- Sync could freeze the entire device partway through a large library:
  - Added a wake lock for the duration of a sync, since long syncs could
    outlast the screen timeout and let the CPU suspend mid-transfer.
  - Fixed a connection leak on failed downloads (unclosed response body).
  - Sync progress is now saved periodically instead of only once at the
    end, so an interruption doesn't lose all prior progress.
  - Throttled UI progress updates during long runs of skipped (already
    synced) files, which could otherwise flood the main thread.
- Downloaded files could be silently truncated if the connection closed
  early without an error — added a content-length check so a truncated
  file is now correctly treated as a failed download instead of being
  recorded as successful. (Root cause of M4A/ALAC tracks failing to play
  with an extractor error after sync.)
- System Update page's metadata and APK download both failed with
  "Network error" — replaced fragile legacy HTTPS handling with the same
  OkHttp approach already proven elsewhere in this app.
- Date & Time screen: opening it left nothing focused (no highlight,
  clicks did nothing) because a hardcoded "focus child 0" pointed at a
  header instead of a row once the Automatic section was added. Same bug
  fixed in the new About Device screen before it shipped.

### Added
- Reboot option next to Power Off, with the same confirmation-dialog
  pattern.
- About Device screen: library counts (songs, artists, podcasts,
  audiobooks, videos), storage used/available, a couple of fun facts
  (estimated back-to-back listening time, "finish date if started today"),
  and credits (original launcher: ismileblue; this fork: Budm).

### Changed
- Sync progress indicator moved from top-right to bottom-right corner.
- OTA APK download now reads a full URL from `output-metadata.json`'s
  `outputFile` field instead of joining a filename onto `SERVER_BASE_URL` —
  needed since the APK is now hosted at a different path than the
  metadata JSON itself.

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
