# Polaris Test 09 — official YT Music background search remote

**Source-only experiment until CI passes; do not merge into stable v0.58.**

## Why this particular experiment
The Note3 official YouTube Music 9.41.52 APK/MediaSession dump shows:
- `com.google.android.libraries.youtube.player.background.service.BackgroundPlayerService` is active and not exported;
- the active `YouTube playerlib` MediaSession advertises `ACTION_PLAY_FROM_SEARCH`;
- `MusicActivity` handles `android.media.action.MEDIA_PLAY_FROM_SEARCH` externally, but launching it might change the foreground UI.

This experiment calls `android.media.session.MediaController.TransportControls.playFromSearch(query, Bundle.EMPTY)` on the **already-active official YTM MediaSession**, with **explicit phone-screen user tap**. It neither launches an Activity nor starts a private service.

## Test 09 phone checklist
1. Install `Polaris YTM Search TEST 09` over the earlier test APK, keeping the same separate package/signing identity.
2. Ensure YT Music has previously started a track; play or pause it, then return to Polaris.
3. Under Polaris **YT Music**, input a specific song and singer into the **Test 09** search field.
4. Tap `▶ 원본 YT Music 세션에 검색 재생 요청` **once**. No automatic retry.
5. Wait approximately 5 seconds: inspect **request result**, **2-second source status**, **5-second source status**.
6. Independently verify: did the selected song actually play, did the official YTM Activity remain background, did the Polaris phone UI remain visible?
7. Only after phone results are confirmed, separately test Android Auto *while stationary* to see whether Polaris remains the visible AA application.

## Interpretation
- **'Request sent' does not mean playback succeeded.** Android's `playFromSearch()` is a void transport command and has no playback acknowledgment.
- The command is sent only if the source session advertises `ACTION_PLAY_FROM_SEARCH`. Otherwise, do not attempt guessing or a hidden fallback Activity.
- If the original YTM MediaSession is absent, the probe stops without auto-launching YTM or claiming cold-start support.
- The query is entered manually. This does not yet test Assistant speech routing or change its selected default media app.
- The source session may play a different song than requested. Report actual title and playback state; do not infer correctness from a state change alone.
- Do not change radio/NAS MP3/CAN, nor store account cookies, login data or user queries.
- Retains earlier Test07 playlist shared-link bindings and Test08 MediaSession diagnostic.

## Technical implementation
- `PolarisYtmSearchQuery.java`: pure Java input checks; 16 JVM fixtures.
- `PolarisYtmSearchCommandProbe.java`: only the installed official YT Music package is considered; requires existing user-granted notification access to inspect media sessions.
- `MainActivity.java`: explicit button, asynchronous command dispatch, two follow-up state inspections. Does not invoke Polaris' audio player or change source selection on command.
- Existing CI also runs all earlier Java parser/playlist tests and Android build/signature checks.
