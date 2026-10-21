# Hush Lite: YouTube Music core isolation — stage 1

**Status (2026-10-22):** Source slicer committed; upstream compile and playback NOT YET verified. **No APK**.

## Goal

Strip Hush *before* any attempt to integrate its YouTube Music features into Polaris. Keep the
proven upstream behavior as the reference: YouTube Music account/login, song search, library
and playlists, stream playback/cache, Android Auto voice controls. Eliminate Spotify,
SpotiFLAC, Tidal/Qobuz, Waze bridges, lyrics, canvas, equalizers and other nonessential
UI/services only after dependencies have been untangled.

## Actual work in this stage

A **repeatable, destructive-free** source generator under `scripts/stage_core.py` extracts
the unmodified, GPL-3.0 Hush **JVM InnerTube core** and the Gradle dependencies/version
catalog into a new, independently buildable *library-only* project.

- Original upstream: `maxinjohn/Hush v13.14.7`
- Pinned commit SHA: `8f3e55d1114f1b844f0a21af8c365d090a43799f`
- Included: `core/`, `gradle/libs.versions.toml`, original `LICENSE`, minimal Gradle roots.
- **Excluded**: complete Android `app/` plus Spotify/lyrics/Waze/SpotiFLAC/benchmark/other
  modules. The excluded features have NOT yet been removed from the Android runtime
  because that runtime has deliberately not been ported in stage 1.
- No merge into stable Polaris `main`, B, C or external H01.
- No use of original YouTube Music as the future playback dependency.

### Run locally / in CI

```bash
git clone --branch v13.14.7 --depth 1 https://github.com/maxinjohn/Hush.git hush-original
python3 -m unittest discover -s hush-lite/tests -v
python3 hush-lite/scripts/stage_core.py --upstream hush-original --out /tmp/hush-lite-core
gradle -p /tmp/hush-lite-core --no-daemon :core:compileKotlin
```

Requires **JDK 21 and Gradle 9.6.1**. The generator refuses unexpected Hush revisions,
overwriting any existing output path, or a missing essential source file. It retains the
upstream license and all original `core` contents unchanged.

The GitHub workflow `hush-lite-core.yml` runs tests and *compiles the JVM core only*.
It does NOT invoke `assemble` and explicitly checks no APK output exists.

### Next isolated steps after core compilation

1. Build a minimal Hush **Android** shell with Google/YTM WebView login handling and
   account session persistence, eliminating unused Hilt/Room/UI dependencies one at a time.
2. Implement Hush-internal song tap -> `WatchEndpoint(videoId)` -> queue -> stream
   resolver/cache -> ExoPlayer. Do not re-search a selected song by text.
3. Add the **single** AA media library/session and `onSetMediaItems` voice search path.
4. Verify fast playback on the phone and in Android Auto, then integrate into a Polaris
   experiment off v0.58. Source-specific audio focus and retries must be serialized.
5. Do not claim stable performance or release an APK before functional validation.

## Critical caveat

The original Hush login and player are NOT self-contained in `core`:
`auth/YouTubeLoginRepository.kt` imports Android DataStore and Hilt, while
`playback/MusicService.kt` imports Hilt, Room, Spotify and SpotiFLAC systems.
Therefore this stage is **not Hush Lite as a music player** and cannot be installed.
It is the first verifiable slim module, not a cosmetic or fake complete build.

Upstream licence: https://github.com/maxinjohn/Hush/blob/v13.14.7/LICENSE

## Parent project protection

Branch `experiment/hush-lite-core-01` is off `experiment/hush-native-01`
(which was created off stable v0.58); H01 external bridge PR #75 was closed without
merge. B/C are abandoned as an implementation strategy but historical code is retained.
