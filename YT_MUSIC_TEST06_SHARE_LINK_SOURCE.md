# YT Music share-to-Polaris: source-only Test06 handoff

## Proven by Test05 on phone
- The original YTM app's visible library can be observed with user-enabled Accessibility.
- Test05 displayed 6 *candidate* labels, some UI controls rather than valid playlists.
- Anonymous InnerTube Home HTTP 200 returned `itemSectionRenderer -> messageRenderer`, not an observed music shelf. This route has NOT demonstrated personalized Home access.
- Accessibility labels contain names and metadata, NOT guaranteed original playlist IDs.

## New source-only functionality
1. On the official YT Music **phone app**, open one actual sharable playlist and use its **Share** control.
2. Choose Polaris in the Android text share chooser. MainActivity registers `ACTION_SEND` with `text/plain`; there is a manual **paste shared link** fallback in the Polaris YT Music tab.
3. Strictly parse a legitimate HTTPS `music.youtube.com/playlist?list=<ID>` or `www.youtube.com/playlist?list=<ID>` URL. Reject song-only links, unknown domains, shortened URLs, malformed IDs and unrelated text. No login token, background scraping, or network URL redirection.
4. The user explicitly chooses the matching captured playlist title, or manually enters the real title if it has not yet been captured. A confirmation screen shows the actual shared ID before writing the local binding.
5. The bound item appears under Polaris **YT Music > Library** on phone and AA, clearly flagged as user-confirmed share-linked. Opening it exposes `ytm:openplaylist:<ID>` to the existing original YT Music link dispatch. On phone, dispatch `ACTION_VIEW` from the foreground Activity instead of the media service.
6. Bindings persist in TEST-app preferences only and can be removed without modifying original YT Music account data.
7. Raw accessibility labels (without a confirmed shared ID) remain nonplayable and never get a guessed ID.
8. Existing misread Test05 labels (`고정됨`, `오프라인 저장한 재생목록 표시`, `오프라인 저장한 곡 표시`, `스마트 오프라인 저장 완료`) are filtered both for new scans and on loading old cached snapshots.

## Validation status
- `PolarisYtmSharedPlaylist` and its exact **15 fixture cases** were compiled with local Java 21 and all passed.
- Previously verified pure-Java label and deeplink suites from Test05 are retained. Five screenshot false-positive label tests were added. The Android UI, Manifest, service, target-device sharesheet integration and Android Auto have **not** been compiled or run in this branch.
- Future GitHub Actions CI has been prepared to run all three host-JVM test suites; CI has not been invoked here.
- No APK has been created, no PR opened, and the stable v0.58 main branch remains untouched.

## Exact first device experiment after a requested Test06 build
1. Phone: install versioned Test06 separate package `com.polarisunbound.app.test`.
2. In original YT Music open one actual **shareable** playlist, Share -> Polaris, or copy its original playlist sharing link and paste it into Polaris.
3. Choose/enter the exact playlist title, verify ID, and confirm binding.
4. Polaris > YT Music > Library > the linked playlist > **Open in original YT Music**.
5. Verify that the correct original playlist opens. **Do not count opening as verified autoplay.** Manually press play in the original app and then verify mirrored media session controls.
6. Only after this phone path works, test the same browse path on *wired* Android Auto without simultaneous ADB.

## Current unsupported situations
- Some auto-generated Likes/offline playlists have no share action and cannot be linked using this method.
- Share URLs not containing a direct `/playlist?list=` URL cannot be linked without new, separately tested handlers.
- Automatically importing every personalized album/shelf, discovering all playlist IDs from Accessibility, or remotely starting playback from AA are not demonstrated by this workaround.
- This is the **first reliable one-playlist identity proof path**, not a solution for account-wide Home/Library navigation.

## Next source-validation checkpoint (2026-10-18)
- Found and fixed a missing Android Auto browse refresh path: opt-in YTM accessibility snapshots already sent the package-scoped `YTM_SNAPSHOT_UPDATED` broadcast, but the media browser had **no receiver**. `PolarisMediaService` now registers a same-app, non-exported dynamic receiver while running, notifies `ytm:library` and an optionally changed captured playlist's children, and unregisters on destroy. MainActivity broadcasts after user-confirmed bind/unbind. When the AA browser is not running, the next browse starts from current saved data.
- This refresh is an Android code path modification, **not yet compiled nor device-verified**. In particular, AA hosts may still cache their own pages and Android background activity launch restrictions remain unverified.
- A strict share-to-original-app ID preservation regression test (`PolarisYtmShareRoundTripTest.java`) was added.
- Actually ran Java 21 tests in a local sandbox after copying the current pure-Java branch code: **19 visible-label cases, 15 shared-link parser cases, and 7 ID round-trip cases**, all passed (41 in total). This covers pure Java behavior only. Live YTM share chooser, storage, manifest, MediaBrowserService and Activity have **not** been built/installed/tested in this branch.
- The future CI workflow now includes all four JVM test entrypoints before Android compilation; **not triggered** because no APK build was requested.

## Installation/build policy
- Do not create a Test06 APK from the shorthand "next" alone.
- When the user explicitly requests the Test06 APK, bump versionCode from 5905 to 5906, update app label and artifact/test CI identity, trigger isolated test PR build, confirm CI JVM tests + Gradle + signature + APK package ID, and test on phone before AA.
- Stable v0.58 on main remains untouched.
