# Polaris C01 — standalone YT Music text-to-play probe

**This is a separate Android application**, built from scratch under `c-version/` with package `com.polaris.cprobe`. No Polaris media session, radio, NAS, CAN, Android Auto interface, notification access, cookies, local media playback, or reused Polaris code.

Only two visible widgets: a text input and an execute button. Enter a song/artist query and press Execute.

1. C01 sends the text to YouTube Music's **public, anonymous** web search endpoint (`music.youtube.com/youtubei/v1/search`) and parses the first playable song/video ID.
2. C01 sends the **specific** `https://music.youtube.com/watch?v=VIDEO_ID` link via `ACTION_VIEW` with package restricted to the **official installed YT Music app**.
3. C01 **never** plays or downloads a stream itself.

This bypasses the known unreliable `MEDIA_PLAY_FROM_SEARCH` intent that can merely open a search UI without playback. The InnerTube API is unofficial and may change; Google/YT Music may decide not to autoplay the incoming deep link. An Android intent dispatch alone is *not proof* of playback; check what happens in the installed YT Music app. If a public search fails or the YT Music app is not installed, the app shows a short error and will not silently substitute a different player.

## Build

Use JDK 17 and Android SDK 35:

```
cd c-version
gradle --no-daemon assembleDebug
```

APK: `c-version/app/build/outputs/apk/debug/app-debug.apk` (debug signed).

For meaningful testing on the phone, first have the official YouTube Music app installed and signed in (if its track requires Premium). Type an unambiguous song/artist such as `Clazziquai Maybe Baby` and tap Execute. Observe whether the official player actually starts the requested song; if it only displays an album/search page, record that as a **playback failure**.

## C02.1 install fix

C01 (package `com.polaris.cprobe`) and the first C02 (same package but a different ephemeral debug signing key) could not coexist or update. The first C02 caused Android to show **앱이 설치되지 않음** on a device that had C01 installed. The v2 and v1 APK certificate SHA-256 values are different.

C02.1 now uses the **distinct package** `com.polaris.cprobe.c02`, so it installs **beside C01**, without uninstalling or overwriting it. Version is `C02.1-install-fixed`, versionCode 3. Search, matching and official YT Music deep-link logic are unchanged. The CI build always restores the existing stable debug signing key from `.github/polaris-debug.keystore.b64` and signs the C02.1 build with it, so future C02.x APKs with this same package/signing method can update normally.

For a local build first restore the repository debug signing key:

```bash
base64 -d .github/polaris-debug.keystore.b64 > .github/polaris-debug.keystore
gradle --no-daemon -p c-version :app:assembleDebug
```

Please do not delete the working C01 until C02.1 passes device tests.
