# 6.04 fixes being prepared from 6.03

Radio source changes are in radio604.patch. Apply after the original pinned Polaris integration and native_603.py. This is a source patch, not a completed release or a claim of live station validation.

- Add media3-exoplayer-hls using the shared Media3 version.
- Reference HlsMediaSource.Factory directly for radio items explicitly marked as HLS, keeping the HLS path compile-time reachable in release.
- Avoid repeating a station name in both title and artist when programme information is absent.
- Refresh artist and artwork as well as title, preserving the existing URI and live configuration.
- Add five focused JVM regression tests for duplicate labels, programme titles, missing data and overseas clocks.

Completed checks: git diff --check; exact reverse-application to the modified 6.03 source; shared Media3 version-catalog reference; official Media3 1.8.0 factory constructor checked.

Not yet run: JVM tests, Android release compilation, final APK HLS packaging and playback, AA title refresh on a head unit.

Pending streaming scope: actual-playback403 feedback, rejection of the failed song/token combination and validation of audio data. The radio patch does not implement or prove that streaming fix.

No release version bump, workflow assembly, APK generation or new data namespace is included in this patch.
