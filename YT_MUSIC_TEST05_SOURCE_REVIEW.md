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
The parser was compiled and exercised locally against **11 fixtures** (Korean and English labels, automatic playlists, embedded/bullet/multiline descriptions, album label, duplicate entries, mixed items, and invalid navigation-only content), all passed. This verifies only the pure Java label-classification logic. Android app compilation and live YT Music extraction remain unverified.

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
