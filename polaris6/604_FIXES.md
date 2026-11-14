# Polaris 6.04 (100604)

Base: Polaris 6.03, pinned InterTune v0.12.10 commit 4ff39e9e12a20f93d5dfd24c52fe925cbbb50020. Integration order: original integration patch, native_603.py, radio604.patch, stream604.patch.

## Changes

- Add Media3 1.8.0 HLS module and an explicit radio HLS factory. Songs still use the InterTune media source factory.
- Suppress a duplicate station artist when the programme title is the station name. Programme metadata updates preserve the current stream URI, live settings and tag.
- Check both initial and distant 16-byte web stream ranges. Require HTTP 206, the matching Content-Range and all requested body bytes. A HEAD success or a partial response status by itself is insufficient.
- Feed an actual player/load HTTP 403 back to the exact URL selection before discarding the URL. Keep bounded, process-only song/account-or-visitor/token-choice rejection memory for ten minutes. Prefetch of another song cannot overwrite the rejected song's selection. Duplicate failure callbacks do not extend its lifetime.
- Retain InterTune token generation, client routing policy, cache validation and unavailable-song logic. No authentication/session state is imported from another app.
- Retain 6.03 database, preferences and download/cache namespaces. Update version and keep the installed package and signer.
- Keep the tracing API reachable for minified release instrumentation. Previous release tests stopped inside the runner before testing the service.

## Validation

CI builds and tests the minified core release, verifies the manifest and original signing certificate, then installs it on Android 13. JVM coverage includes real loopback HTTP range/body responses, actual-refusal token changes and existing recovery/radio tests. Release instrumentation checks browse/voice/report behavior and prepares a real local HLS audio fixture through the radio factory.

The build run and exported test results are the authority for pass/fail. A local fixture does not establish that a particular signed-in YouTube song or a broadcaster's live endpoint will play. The original healthy VVUvWmHAWDQ evidence used cached audio; it did not demonstrate a successful fresh fetch. This change does not restore missing library tracks or migrate another app's 166-song library.
