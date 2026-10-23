# Hush Lite — upstream-first reduction (new baseline)

**Upstream:** maxinjohn/Hush tag v13.14.7, commit `8f3e55d1114f1b844f0a21af8c365d090a43799f`.

This experiment is **NOT** the old HLT01–03 mini search application. It starts from the **complete, working Hush source tree**, preserving the original Compose UI, InnerTube search, playback service, Media3 voice handling, queues, stream resolver, and Android Auto integration.

## Reduction strategy
1. Check out the exact upstream commit in a CI sandbox. Do not copy individual classes into a replacement app.
2. Build upstream `gmsMobileArm64Debug` without any reduction as a baseline. This is a build gate, not a claim of device functionality.
3. First safe reduction: exclude only the `:benchmark` Gradle module from settings and disable the Android baseline-profile Gradle plugin where compatible. Keep **all runtime modules** and original app classes until baseline compiles.
4. Only after baseline succeeds, remove **one unrelated feature at a time** (Spotify/SpotiFLAC, Waze, Discord, optional lyrics/providers) with compile checks and Android manifest checks after each step. Removing code solely by deleting folders is unsafe because of Hilt and navigation references.
5. Never remove `MusicService`, `MediaLibrarySessionCallback`, `YouTubeQueue`, `InnerTube`, login, cache, stream resolver or original Hush search UI.

## Important
- Current HLT01–03 app is not the reference implementation and is not used for this upstream build.
- Keep Polaris stable v0.58 unchanged. This branch is isolated and not merged.
- Hush upstream GPL-3.0 license must be respected for derivative distribution.
- No APK should be distributed as 'working' without install, search, playback and AA voice testing.
