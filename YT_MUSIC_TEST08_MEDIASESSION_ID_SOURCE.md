# Test 08: YT Music ID 자동 노출 검증 (SOURCE ONLY)

## 1. Verified on-device facts from Test 02–07
- Android MediaBrowser connection to installed official YTM was rejected in Test02.
- Read-only opt-in Accessibility captured visible, signed-in YTM library names and counts, including some UI controls that were initially misclassified.
- Anonymous `FEmusic_home` HTTP 200 returned `itemSectionRenderer -> messageRenderer` and no usable home shelves. HTTP 200 does **not** imply personalized home content is returned.
- Test07: Sharing the official `citypop` playlist to Polaris produced a real `list` ID, and choosing the share-linked entry opened exactly that playlist in the original YTM app.
- **Still unverified:** automatic discovery of each private playlist ID without sharing, personal Home traversal and Android Auto autoplay.

## 2. Official documented APIs (research, not code-level speculation)
- Android `MediaController.getMetadata()` exposes information only if the original media app elects to provide it, and `MediaController.getQueue()` may return null.
- `MediaMetadata.METADATA_KEY_MEDIA_ID` is a service-specific content identifier, **not automatically** an original YTM playlist ID.
- `MediaMetadata.METADATA_KEY_MEDIA_URI` may contain a media URI but must be inspected; a track-stream URL is **not** a playlist URI.
- YouTube Data API `playlists.list(mine=true)` can give real IDs for user-owned playlists, but requires user OAuth consent and does **not** cover every YTM automatic/Home shelf. In previous development the user asked to avoid separate login. This implementation therefore does not introduce OAuth.

Docs:
- https://developer.android.com/reference/android/media/session/MediaController
- https://developer.android.com/reference/android/media/MediaMetadata
- https://developers.google.com/youtube/data/reference/rest/v3/playlists/list
- https://developers.google.com/youtube/data/sample_requests

## 3. Source-only diagnostic in this branch
- Added `PolarisYtmSessionIdentityProbe.java`, on-demand read-only check via *already-approved* notification listener and `MediaSessionManager.getActiveSessions`, filtered **only** to `com.google.android.apps.youtube.music`.
- The Test08 phone YTM tab has a new `원본 YT Music 재생목록 ID 자동 노출 진단` button. Unlike the YTM playback bridge, it can inspect the original active session without switching/stopping local radio or NAS media.
- It checks whether source metadata includes an opaque `MEDIA_ID` or `MEDIA_URI`, whether a playback queue and queue items are exposed, and whether **any URI is structurally an official** `https://music.youtube.com/playlist?list=<ID>` URL.
- It prints no generic raw media URI, session extras values, cookies, tokens or song titles. Only explicitly verified *URL-format* `list` IDs can appear in diagnostics. Such validation is structural only; it does not verify server access or ownership.
- Added `PolarisYtmSessionIdClassifier.java` and 14 offline test fixtures preventing accidental promotion of opaque media IDs, nonplaylist song URLs or lookalike domains to playlist IDs.
- Existing Test07 original playlist-opening feature and share-link fallback remain unchanged.
- Future CI workflow includes the new fixtures before Android assembly. **No APK build or PR created for this diagnostic branch**.

## 4. Decision rule on the next phone test (after an explicit APK build request)
1. Play a track within a real original YT Music playlist, e.g. `citypop`.
2. Open Test08 Polaris phone YTM tab. Tap `원본 YT Music 재생목록 ID 자동 노출 진단`. This is read-only; no new account login or ADB while wired Android Auto is needed.
3. If a genuine official playlist URI is present, compare that ID with the existing user-confirmed `citypop` share ID. Only then consider binding an observed active playlist without sharing.
4. If original YTM exposes only opaque track IDs or no queue/URI, **this specific MediaSession route cannot automatically infer the library's playlist IDs.** No string substitution or guessing by playlist title; review an explicitly user-approved OAuth route or preserve optional manual sharing fallback.
5. Phone first, Android Auto second. No new APK was created by shorthand `다음`.

## 5. Remaining limits
- Active MediaSession is a playback context, not a browsable account-wide media library. Even discovering one media or queue ID is not proof of automated library access.
- Android Auto may restrict background activity launching. Test07 only verified the foreground **phone** `ACTION_VIEW` opened the correct `citypop`; not AA autoplay.
- The pure-Java regression test file is source code **prepared but not run** in the latest branch; previous Test07 CI suites already passed in Test07. Android build and physical Test08 are still pending.
- Stable Polaris v0.58 on `main` is untouched.
