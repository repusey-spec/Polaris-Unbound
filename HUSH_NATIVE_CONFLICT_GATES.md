# Polaris Unbound × Hush — Native YouTube Music migration / conflict gate
Status: ARCHITECTURE AUDIT ONLY — **no Hush code imported, no build, no APK, no phone or AA validation**.
Reviewed: 2026-10-22.

## Firm project decision

- **Do not resume Polaris B or C YouTube Music source implementations.** B/C remain accessible for historical comparison, not development bases.
- **Do not ship H01.** That external-app MediaBrowser remote cannot satisfy the new user goal.
- This branch starts from the **stable v0.58 `main`**, not B Test22, C06, or H01.
- Preserve **Polaris radio presets, NAS/FTP MP3, CAN and AA top navigation**; integrate Hush YouTube Music search, account library, queue, player stream resolver, caching and AA voice handling **inside the Polaris test APK**.
- Installed official YT Music and installed Hush must **not** be required as playback dependencies in the completed version.
- Until source-path conflicts are resolved and validated, do not generate an APK or merge into main.

## Verified upstream pins / build collision

| Component | Stable Polaris v0.58 | Hush v13.14.7 | Resolution requirement |
|---|---|---|---|
| Base repo | repusey-spec/Polaris-Unbound `main` | maxinjohn/Hush `v13.14.7` | Distinct isolated experimental branch |
| Android build plugin | 8.7.3 | 9.2.1 | Pick and **compile-test** one toolchain; cannot assume copy/paste |
| Gradle | CI 8.9 | Wrapper 9.6.1 | Match tested Android plugin |
| JVM/Java | CI Java 17 | Kotlin/JVM 21 | Align JDK + Kotlin compiler before compiling |
| Kotlin | No Kotlin app plugin | Kotlin 2.4.0 + KSP + serialization | Add only required Kotlin toolchain/plugins |
| Media3 | 1.11.1 | 1.10.1 | One resolved Media3 version across player/session modules; test APIs |
| SDK | min 23, compile 36, target 35 | min 26, compile 37, target 37 | Verify target phone compatibility; do not silently raise minSdk |
| Packaging | `com.polarisunbound.app` | `app.hush.music` | Experimental applicationId `com.polarisunbound.app.test`, unique authorities |
| License | Check existing Polaris release obligations | Hush/ArchiveTune GPL-3.0 | Preserve copyright/license notices, assess distribution compliance |

*Versions read from original repository Gradle/manifest sources. An exact dependency graph and build have not yet been proven.*

## Runtime conflict: one public AA entry point and one playback owner

Polaris v0.58:
- `PolarisMediaService : MediaBrowserServiceCompat`.
- `MediaSessionCompat` owns AA playback state.
- Its own `ExoPlayer` plays live radio and saved/local/NAS tracks.
- Own `AudioFocusRequest` plus retry/buffer recovery.

Hush v13.14.7:
- `MusicService : MediaLibraryService`, Hilt-injected dependencies.
- Its own `MediaLibrarySession` and media callbacks own voice requests.
- Own `ExoPlayer`, `AudioManager` focus request, playlist queue, stream resolver and caches.
- `core/src/main/kotlin/app/hush/music/innertube/` handles account-aware search and browse.
- Hush manifest exposes `android.media.browse.MediaBrowserService` and Media3 session actions.

**Blocked collision**: including both exported services intact creates multiple AA entry points / split media state / competing focus owners. Importing full Hush `MusicService.kt` unmodified would also pull Spotify, SpotiFLAC, Waze, lyrics, Hilt, Room and other app-specific dependencies.

**Required design invariant**: exactly ONE Android Auto-facing media library/session in the new APK and exactly ONE authoritative playback-state/audio-focus coordinator. Two source-specific audio players are only acceptable if **mutually exclusive**, and if STOP/PREPARE/focus handling is centrally synchronized; prefer the Hush player for YouTube and preserve Polaris source playback until unified playback compatibility is proven.

Do not register the Hush original media service as an additional AA app. Do not reproduce the H01 external Hush proxy as the main solution.

## Voice dispatch and song identity

- Hush `MediaLibrarySessionCallback.onSetMediaItems()` processes `firstItem.requestMetadata.searchQuery` and obtains actual `SongItem` results via `YouTube.search(..., FILTER_SONG)`.
- `MusicService.playFromVoiceSearch()` also resolves query and starts its own `ListQueue`.
- Android Auto `onSearch` / `onGetSearchResult` are implemented by Hush and should be mapped to a single session.
- `VoiceSearchMatcher.kt` **exists** but is **not directly used** by the observed AA voice queue path. Do not promise artist/title exactness based on its mere presence.
- Song taps must pass the **already-resolved SongItem ID / WatchEndpoint** into Hush queue; do **not** start a second search by artist/title and do not open official YT Music.
- Validate voice query, selected title, selected artist, videoId, first audible frame and player state separately.

## Streaming and NAS/radio separation

- Hush `MusicService.kt` audio source resolver explicitly **bypasses YouTube stream resolution for `http`/`https` and local-file URIs** (`Uri.shouldBypassYouTubeResolver`).
- Polaris radio HLS metadata and retry behavior must remain intact; a common Media3 pipeline is *plausible*, but HLS live-offset and retry must be regression tested before replacing old playback.
- Polaris NAS is **offline-first** (saved URI/cache preferred before FTP download); do not let Hush cache overwrite its saved-file handling.
- Stop pending radio HLS retries and source-specific restoration when changing to YouTube; stop/cancel YouTube resolving jobs when returning to radio/NAS; keep only one source active.
- Prevent old paused/stale MediaSession metadata from overwriting the active source's title/art/play state.

## Integration sequence: build gates, not fake version numbers

A. **Build-system smoke gate**: clean native branch; add only `hush innertube core` and its required modules; align plugin/toolchain/Media3; verify compilation and packaged applicationId. Preserve stable radio/NAS baseline. Fail closed if any incompatible dependency.
B. **Functional playback gate**: port Hush's account session, `SongItem`/playlist queue, playback resolver, BotGuard/auth-dependent pieces and cache. Prove song tap -> Hush internal player -> audible audio with **no installed Hush or official YT Music required**.
C. **AA service collision gate**: expose a single AA browse entry with radio / media / CAN; integrate song browsing and voice media actions; confirm radio<->music<->NAS switching without simultaneous players/focus.
D. **Phone gate**: installed icon, open screen, search exact songs, playlist playback, metadata, art, pause/next/prev, radio and NAS regressions. Do NOT claim success from CI only.
E. **Wired AA gate**: Galaxy S8, AA mic voice targeting Polaris, correct track, fixed UI, focus/resumption, text and artwork. USB ADB cannot be run simultaneously with wired AA.

## Exclusions

Spotify, Tidal, Qobuz, lossless extraction, Waze bridges, podcast features, lyrics, AOD and optional Hush UI are outside the desired YouTube-only scope, except if a dependency turns out indispensable and is explicitly documented.

## Version/APK policy

No APK until the compile/runtime conflict gates are satisfied, and no auto-generation without the user's explicit request. Do not alter `main` or the existing stable package. New test versions must have independent app identity, visible phone launcher, and pass phone tests before Android Auto tests.

### Verified source locations
- https://github.com/maxinjohn/Hush/tree/v13.14.7/core/src/main/kotlin/app/hush/music/innertube
- https://github.com/maxinjohn/Hush/blob/v13.14.7/app/src/main/kotlin/app/hush/music/playback/MusicService.kt
- https://github.com/maxinjohn/Hush/blob/v13.14.7/app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt
- https://github.com/repusey-spec/Polaris-Unbound/blob/main/app/src/main/java/com/polarisunbound/app/PolarisMediaService.java
