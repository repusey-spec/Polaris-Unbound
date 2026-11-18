# Polaris Unbound 6.03 — Native InterTune streaming

**Version:** 6.03 (100603)  
**Package:** `app.hush.music.polarisradio.debug`  
**Source baseline:** InterTune v0.12.10, pinned at `4ff39e9e12a20f93d5dfd24c52fe925cbbb50020`.  
**Supersedes:** Polaris 6.0, 6.01. Polaris 6.02 is explicitly discarded.

## Integration boundary
- **Radio** remains Polaris: existing radio artwork, radio grid/presets, play/stop and station switching, Android Auto radio browsing/voice control.
- **Streaming** is built from the pristine InterTune v0.12.10 app: InterTune Home, search, browsing, playlists, library, downloads, decoder, ExoPlayer stream resolving, YouTube client order, account and playback recovery.
- Only the outer `Radio / Streaming` selector and a media-source classifier belong to Polaris. Radio media items go to Polaris radio; non-radio items delegate to the original InterTune media source, `songs.createMediaSource(mediaItem)`.
- The Polaris icon and local error-report interface remain.
- No source or fixes from 6.01/6.02 are applied to the streaming engine. Neither `YTPlayerUtils` nor `StreamCheck` has the previous tokenless-206 patch.

## Data isolation
The installed application ID remains Polaris for upgrade/signature continuity. Android does **not** allow it to access the separate official InterTune app's private account/cookie, visitorData, database, or cache. **Native source identity does not imply live session identity.** Users must sign into 6.03 separately; it cannot use InterTune's existing private cache without authorized export/import.

To avoid reusing 6.0/6.01 failed-song ('StandIns') entries, 6.03 creates **fresh, isolated** `intertune603-settings`, `intertune603-song.db`, `intertune603-exoplayer`, and `intertune603-download`. Existing Polaris data and radio presets are retained on disk, never deleted or silently migrated.

## Build and validation
The source is reconstructed reproducibly by `polaris6/intertune.patch.gz.b64`, pinned upstream checkout, then `polaris6/native_603.py`. In addition to tests and apktool verification, `polaris6/verify.py` compares the byte content of native InterTune stream files against the upstream checkout and rejects any 6.01-specific 206 patch. Build signs with the original Polaris signer and verifies install identity. Emulator smoke checks the Radio and Streaming tabs and crashes.

A passing compilation, unit test, or emulator smoke test is **not proof** that a particular YouTube Music song plays on the Galaxy S8. Live playback must be validated separately on device.
