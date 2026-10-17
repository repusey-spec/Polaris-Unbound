# Polaris Test 11 — clean signed-in YT Music playlists

VersionCode 5911 / package `com.polarisunbound.app.test`. Stable Polaris v0.58 untouched.

## What changes

**YT Music tab** is now a simple playlist interface, not a laboratory menu:
- **계정 관리/계정 연결** and **↻ 새로고침**: the only action buttons at the top.
- Compact actual playlist rows: selecting one opens real song-title and artist rows.
- Tapping a song sends an official YT Music watch deep link (autoplay **not guaranteed**).
- Removes old media browser diagnostics, accessibility setup, manual share-link buttons, Test09 text search, redundant original-YTM app launcher, the giant stop button, and all long explanations from the YTM *screen*. Underlying radio, MP3, CAN, and session relay stay intact.
- AA YTM root shows the authenticated playlist folder and current track, not the old public/discovered folders.

## Fix for wrong account/channel

Test10 sent `X-Goog-AuthUser: 0` regardless of the YouTube profile shown in the Polaris WebView. Test11:
1. On explicit login confirmation, reads numeric `SESSION_INDEX`, optional `DELEGATED_SESSION_ID` or `DATASYNC_ID` from the **currently displayed official music.youtube.com page**.
2. Persists only the selected index and channel page ID in app-private preferences; auth cookies stay within Android's WebView cookie storage.
3. Uses the selected index as `X-Goog-AuthUser` and (when present) channel as `X-Goog-PageId` and `context.user.onBehalfOfUser` in read-only `WEB_REMIX` account browsing.
4. Reads up to 10 pages / 450 actual rows, deduplicates by true ID and protects against repeated continuation tokens. Explicitly rejects server responses that mark `loggedOut`.

**This is a hypothesis-driven correction**; user-selected account may still be wrong if Google changes how it exposes session metadata. Success requires the genuine `citypop` playlist and its 49 entries to appear on the *native Polaris list*.

## Test on Galaxy S8, phone first

1. Install Test11 over Test10, preserving app-owned WebView cookies.
2. Go to **YT Music → 계정 관리** once, wait for your logged-in YT Music home, then press **내 플레이리스트로 이동** to capture the selected channel identity.
3. The native Polaris playlist screen should load automatically. It shows the account index and presence/absence of channel page ID, **not sensitive identifiers**.
4. Tap **citypop**, confirm the actual song list, tap one track and check the official YTM is playing the correct selection.
5. Only after phone validation, open AA while stationary.

No additional login credentials or copies of session cookies needed. Do not share actual cookies or tokens.

The original app's sign-in remains separate from Polaris' login. Private InnerTube APIs and embedded WebView authentication may change or be restricted. A successful APK build does not prove that the server returns the correct account, and YTM deep links can open without starting playback.
