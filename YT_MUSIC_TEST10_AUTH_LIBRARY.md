# Polaris YTM Login / Authenticated Library — Test 10

**Experimental separate APK only. Do not merge into stable v0.58.**

## What this version actually adds
1. Phone app `YT Music` tab > `1. Polaris에서 YT Music 별도 로그인 / 계정 변경` opens a **separate Polaris-owned WebView** at Google's own HTTPS login URL. This **does not** read the original official YT Music app's cookies or logged-in data.
2. `2. 내 YT Music 플레이리스트 자동 조회` reads the opt-in WebView session cookies, constructs an authenticated read-only WEB_REMIX browse request for `FEmusic_liked_playlists`, and displays **real playlist titles and browse IDs**.
3. Tap a playlist to request `VL<actual_playlist_id>` and parse **actual song title / artist / 11-character videoId**. Continuations are followed at most 10 pages / 450 entries.
4. Tap a song to send an official `https://music.youtube.com/watch?v=<actual_videoId>&list=<actual_playlistId>` `ACTION_VIEW` deep link specifically to `com.google.android.apps.youtube.music`. The original YTM app controls playback. **This dispatch does not guarantee autoplay**.
5. The logged-in playlist folder is also available inside the Polaris **AA browse hierarchy** (after phone login). Existing original YTM MediaSession relay / remote stays intact.
6. Retains earlier tests; radio, NAS MP3, CAN, stable v0.58 are unchanged.

## Login, privacy and compatibility boundaries
- Google may block embedded WebView login, as sign-in inside an embedded app is not a supported OAuth flow. If blocked, do not retry by entering credentials into Polaris' own fields; the app has none.
- The user sees Google's actual domain and enters credentials **only into Google's own HTTPS WebView page**; the WebView remains inside Polaris' app sandbox. No automatic access to official YT Music's login session.
- CookieManager holds the WebView session inside app storage. No password/cookie/authorization string is printed to UI, service logs or SharedPreferences. The built-in `Polaris 웹 로그인 데이터 삭제` action clears *this app's WebView cookies*.
- YT Music's WEB_REMIX/InnerTube endpoints are unofficial/subject to change; 200/401/403 is not equivalent to a verified working playlist response.
- Account-specific YT Music music-upload content and all other personalized screens are not in scope. No independent audio engine.
- This version is intended for **phone-first verification**; on AA, external Activity launches may be suppressed or change visible media app.

## Minimal real-phone verification
1. Install `Polaris YTM Login TEST 10` (test package remains `com.polarisunbound.app.test`, same signing key as Test09).
2. Go to YT Music tab > **1. Polaris에서 YT Music 별도 로그인** and complete Google's login, if available.
3. Tap **로그인 상태 확인 → Polaris 보관함으로** when the WebView shows your signed-in YT Music Home.
4. Tap **2. 내 YT Music 플레이리스트 자동 조회**.
5. Expected: actual `citypop` and other playlist titles. Tap `citypop` to see actual song titles and artists.
6. Tap one song; record **which exact song the official YT Music plays** and whether Polaris foreground is retained. A successful link open is NOT playback confirmation.
7. Only after phone validation test Android Auto while stationary.
8. Report displayed error string if no login or playlist data. Do not send Google passwords, auth cookies or debugging dumps that include token values.

## Architecture
- `PolarisYtmLoginActivity.java` — HTTPS-only embedded WebView, user-controlled login/logout.
- `PolarisYtmAccountCatalog.java` — bounded background read-only personalized playlist/song browse; SHA-1 SAPISIDHASH authentication uses cookie in memory; no cookie persistence in app preferences.
- `PolarisYtmAccountIds.java` — strict ID validation.
- `PolarisYtmCatalog`, `MainActivity`, `PolarisMediaService` — wire account browse IDs into phone and AA navigation; unchanged existing original-YTM deep link transport.
- Independent implementation based on documented behavior and code inspection of Metrolist (GPL-3.0) and existing YT Music clients; **do not copy GPL source into Polaris** without license review.

## Known limitations & falsifiable checks
- **WebView sign-in can fail** even while Metrolist or official YTM can sign in elsewhere.
- **Authentication cookie detection is not proof that browse succeeds**: the Test10 server result must be populated with real entries.
- Page parsing is intentionally narrow: actual `musicTwoRowItemRenderer` playlist entries and `musicResponsiveListItemRenderer` songs. Site changes need parser updates.
- Selection uses YTM `ACTION_VIEW` deep link, not a proven MediaSession play-from-ID path.
- This is a genuine buildable test with unverified device behavior; do not claim it achieves full user goal until the screen and playback are confirmed.
