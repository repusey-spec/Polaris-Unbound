# Polaris Unbound — YT Music browse Test 03 (isolated)

This test is **NOT** Polaris stable v0.58. Its Android application ID is
`com.polarisunbound.app.test`, versionCode `5903`, and app-drawer label
**Polaris YTM Home TEST**. It updates the old integrated TEST app only.

## What is actually implemented
- Existing radio / NAS MP3 / CAN pages and YT Music MediaSession mirror remain.
- AA: `Polaris → YT Music → 홈 · YT Music 공개 추천` reads **real**
  unauthenticated WEB_REMIX `browse(FEmusic_home)` results (not user's logged-in home).
- Open a returned shelf, then real playlist/album folders. Playlist tracks use
  the server-returned video IDs and can be handed to the **installed original
  YT Music** through an `ACTION_VIEW` deep link. Starting playback from the
  link, and activity launches from AA, require **physical-device verification**.
- AA: `Polaris → YT Music → 보관함` reads only labels the original app actually
  exposed in the foreground to our opt-in accessibility service. This does NOT
  reveal private playlist IDs; such items are deliberately non-playable.
- Phone YT Music tab: **홈 탐색**, **보관함 탐색**, **보관함 목록 읽기 권한 설정**.
  Home/Library share the same real-data catalog as Android Auto.

## Initial phone verification (before connecting wired AA)
1. Install Test 03 APK. Confirm the phone app drawer contains **Polaris YTM Home TEST**,
   which does **not** replace Polaris stable.
2. Start the official YT Music app, play a song, and enable notification access
   for the Polaris test app if needed. Verify existing YT Music relay controls.
3. On the Polaris test phone YT Music tab, tap **홈 탐색**. A successful online
   request should display real shelves and folders; a network/InnerTube failure
   should show an explicit error, never made-up entries.
4. To test your actual private library names, use **보관함 목록 읽기 권한 설정**,
   enable only the test accessibility service, then visit YT Music's **보관함**
   screen while the phone screen is on. Return to Polaris Test 03 and select
   **보관함 탐색**. Confirm only genuine visible names are listed; songs/playlist
   IDs from accessibility are *not* fabricated.
5. Test online music entry: when selected, it should try to open the *original*
   YT Music app. Whether it autoplays is not yet verified.

## Wired Android Auto verification (after phone verification)
Connect Android Auto, open **Polaris YTM Home TEST** and browse the YT Music folder.
Check the public home shelves, nested entries and the visible-library snapshot.
Track/playlist activation may be blocked by Android's background activity launch
policy; record the result rather than claiming success.
The Galaxy S8 wired-AA test does not use simultaneous USB ADB.

## Engineering notes
- Read-only anonymous InnerTube browse, fixed client ID/version based on Hush
  v13.14.7, reconstructed minimal Java parser; fragile if Google's response changes.
- No official-app cookie extraction and **no personal home recommendations** from the
  network without authentication; personal library *names* come from UI only.
- Accessibility scope: official YT Music package only, read-only, no tapping,
  scrolling, passwords, cookie access or account export.
- Source examples: Hush MediaLibrarySessionCallback/YouTube; Metrolist
  MediaLibrarySessionCallback; Companion WEB_REMIX browse protocol.
- Stable original never receives any changes. DO NOT MERGE the draft PR.

## CI
`Polaris YTM Home + Library Test 03 APK` Actions workflow builds the APK, verifies
signing/identity/versionCode 5903, and publishes
`Polaris-0.58-YTM-Home-Library-Test-03` artifact.
