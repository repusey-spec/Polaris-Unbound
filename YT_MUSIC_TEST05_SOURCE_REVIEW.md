# Polaris YouTube Music source-only checkpoint

## Current verified observations
- Test 04 returned HTTP 200, visitor=true, sectionNodes=1, shelfTypes=0.
- We have **not** proven whether that response contained playable music or a signed-out screen. The next phone test will surface the actual root node type.
- Accessibility and notification-listener switches are enabled in the user's screenshots. The library shown by Test 04 was empty.

## Changes in this branch (not shipped as an APK)
- Nested Home renderer traversal plus response-shape-only diagnostics. Never invent Home shelves or account entries.
- Accessibility scan counts and error details to distinguish: no YTM foreground event, text not exposed, and text exposed but no matching metadata.
- Standalone `PolarisYtmLabelParser` supports separate title/type labels, inlined title/type descriptions, multi-line descriptions, localized middle dots, automatic playlists.
- Fixed a regression where `앨범 · artist` was accidentally interpreted as an inline artist record.
- Cache merges genuine visible entries while scrolling; existing Test 04 replaced the entire library snapshot with only the most recently visible screen.

## Verified host-JVM fixture coverage
The parser was compiled and exercised locally using `javac 21` against **14 fixtures**, all passed **after fixing a previously incorrect multiline test literal**. The initial exact GitHub test execution failed on a literal `\\n` (backslash + `n`) instead of a real newline; this was corrected, and three additional fixtures were added (offline automatic playlist, full-width punctuation, and navigation false-positive). This verifies only the pure Java label-classification logic. Android app compilation and live YT Music extraction remain unverified.

To repeat the source-level JVM test from the repo:

```bash
mkdir -p /tmp/polaris-ytm-jvm
javac -encoding UTF-8 -d /tmp/polaris-ytm-jvm \
  app/src/main/java/com/polarisunbound/app/PolarisYtmLabelParser.java \
  diagnostics/jvm/PolarisYtmLabelParserTest.java
java -cp /tmp/polaris-ytm-jvm com.polarisunbound.app.PolarisYtmLabelParserTest
```

## Next step and release gate
Do **not** run GitHub Actions assembleDebug or ship Test05 APK without a specific APK-build command. The next deliverable after permission to build will be an isolated 5905 Test05 APK. Test on phone first, then wired Android Auto. Stable Polaris v0.58 stays untouched.

## Additional Home parser review
The original Test 05 version scanned carousel/shelf/grid renderers but skipped `watchPlaylistEndpoint` cards (such as mix suggestions) when they did not have a `browseEndpoint`. The source now maps **real** `watchPlaylistEndpoint.playlistId` to a browsable folder. Responsive song entries can also obtain their genuine video ID from the overlay play button or first title run. IDs are never fabricated.

## Verification limitations
- Only the pure-Java label parser and its 14 regression fixtures have been executed in a JVM.
- The updated Android YT Music Home service/parser, the user-interface collector, Android Auto UI, and APK **have not** been compiled or tested in a live Android installation at this checkpoint.
- No new APK was requested or generated.


## Next-step crosscheck — official YT Music playback handoff
- **Source bug found:** previously `PolarisMediaService.onPlayFromMediaId()` called `enterYtmMode()` (which stops local radio/NAS) before attempting `startActivity()` for the selected YouTube Music link. A synchronous launch failure could silence a working audio source.
- The callback now validates the real browse ID with `PolarisYtmDeepLink.urlFor()`, attempts official-app `ACTION_VIEW` first, and changes to YT Music mode **only if Android accepts the activity launch request**. Invalid IDs and synchronous exceptions leave the current source untouched. This **does not guarantee** Android Auto allowed the activity onscreen or that the target autoplays.
- A separate pure Java test of 13 deep-link fixtures passed using the local `javac`/JVM environment, including valid songs, playlists, URL escaping, missing/invalid input and malicious parameters. Browse ID ownership or playback success cannot be proven by these fixtures.
- Confirmed the existing 14 accessibility label fixtures also pass when locally run. These are unit-level regression results, **not** a successful YTM API call.
- Full Android compilation, official YTM playback test, and APK packaging **still not done** for this branch.

To repeat both host-level test suites:

```bash
mkdir -p /tmp/polaris-source-tests
javac -encoding UTF-8 -d /tmp/polaris-source-tests \
  app/src/main/java/com/polarisunbound/app/PolarisYtmLabelParser.java \
  diagnostics/jvm/PolarisYtmLabelParserTest.java \
  app/src/main/java/com/polarisunbound/app/PolarisYtmDeepLink.java \
  diagnostics/jvm/PolarisYtmDeepLinkTest.java
java -cp /tmp/polaris-source-tests com.polarisunbound.app.PolarisYtmLabelParserTest
java -cp /tmp/polaris-source-tests com.polarisunbound.app.PolarisYtmDeepLinkTest
```
