# Polaris = Radio + Streaming (original Hush)

Status: integration in progress; **no combined APK yet**.

## Exact source references
- Hush original: maxinjohn/Hush tag v13.14.7, commit 8f3e55d1114f1b844f0a21af8c365d090a43799f
- Polaris: app/src/main/java/com/polarisunbound/app/{RadioPresets,PolarisMediaService,CurrentProgramResolver}.java in this repository.
- RadioCatalog.kt in this directory is a **staged source**; it has not yet been copied into the original Hush app module.

## Product UI
- Application title: Polaris
- Radio first, Streaming second; reserve CAN identifier for future, but do not render a CAN tab.
- Android Auto uses Media3 browse tree, not arbitrary top-level tabs.

## Integration rules
- Keep Hush MusicService, MediaLibrarySessionCallback, original search and voice search.
- Implement Polaris radio playback with one shared Hush MediaSession and correct audio focus.
- Do not copy PolarisMediaService or its separate MediaSession into Hush.
- Add browse IDs for Radio and Streaming, and route station selection to a radio-specific media item.
- Retain the live URL resolver, redirect handling, stream error recovery, program metadata, and station ordering.
- Do not treat JSON/API resolver endpoints as direct audio streams.
- Verify app launch and music search on phone before testing Android Auto.

## Build blocker
Previous original-Hush CI failed configuring missing `:lyrics:musixmatch` directory despite shallow submodule checkout. Verify submodule checkout and Gradle settings dependencies before claiming APK output.

## Verification status
- [x] Isolated integration branch
- [x] Station catalog transferred from Polaris source
- [ ] Hush source modified to consume station catalog
- [ ] Hush radio playback engine wired into MusicService
- [ ] Radio/Streaming root browser UI and AA navigation
- [ ] Voice route and playback transition tests
- [ ] APK compile and installation test
