# Polaris × Hush — H01 bridge experiment (2026-10-22)

## Scope

This is an **isolated first integration experiment** based on Polaris B Test22.
It uses the **installed Hush v13.14.7** as the YouTube playback and search backend
and keeps Polaris as the AA UI and transport-control surface. Hush's streaming
engine has **NOT** been vendored or embedded in this commit. Do not describe
this experiment as a completed in-app engine port.

- Stable v0.58 `main`: untouched
- B Test22: untouched
- C01–C06: untouched
- New branch: `experiment/hush-voice-backend-01`
- No APK generated, no device validation

## Changed paths

- `PolarisHushBridge.java`: connects to the exported
  `app.hush.music.playback.MusicService` via MediaBrowserCompat; sends
  `playFromSearch`, `playFromMediaId`, play/pause/skip/seek; mirrors basic
  metadata and playback state to the existing Polaris session.
- `PolarisMediaService.java`: AA search requests default to the Hush session
  while explicit radio/MP3/NAS queries keep the legacy routing; selected
  playable browse rows route by their actual title and artist. A playlist
  selection can use `online_playlist/<ID>`.
- `PolarisYtmCatalog.java`: temporarily retains search text associated with
  displayed song IDs. This is metadata, not a guaranteed track-ID transfer.
- `AndroidManifest.xml`: makes the installed Hush package visible.

## Source evidence and warnings

Hush sources pinned to `v13.14.7`:
- `app/src/main/AndroidManifest.xml` exports a Media3 library service and
  legacy media browser action.
- `app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt`
  accepts a Media3 `requestMetadata.searchQuery` and resolves its own queue;
  it also recognizes `online_playlist/<ID>`.
- `core/src/main/kotlin/app/hush/music/innertube/YouTube.kt` is the actual
  online search and playlist client.
- Hush is GPL-3.0; code extraction for a later embedded engine must preserve
  license obligations.

## Not implemented

1. Importing Hush core InnerTube + stream resolver + audio cache into Polaris;
   H01 still requires Hush installed and logged in.
2. Replacing Polaris's slow account browsing/catalog with Hush library
   browsing; existing Polaris browsing is unchanged in this experiment.
3. Hush like/dislike/repeat/shuffle custom command mapping.
4. Verified exact artist+title matching and voice invocation by Google Assistant.
5. Build and real-device verification. No performance or correctness claims.

## Validation order (do not run unless build authorized)

1. Compile isolated branch; ensure package `com.polarisunbound.app.test`.
2. On phone, with official Hush installed, verify Polaris voice search
   (e.g. `성시경 너에게`) resolves and plays **the correct recording**
   without opening the official YouTube Music app.
3. Verify Polaris play/pause/skip and source switching back to radio/NAS.
4. Only then test wired AA. No ADB alongside wired AA on Galaxy S8.
5. Compare title-click-to-audio latency using matched tracks and conditions.

Do not merge into `main` or B Test22 without explicit approval.
