# Polaris AA UI Probe v0.2 — UI-only implementation

This is a standalone Android Auto media browser prototype installed next to the full Polaris app.

## Actual Android Auto layout
- Top: Home / Radio / Streaming / CAN (exact order)
- Home: current selection only; no invented vehicle data. If nothing is selected, shows no current playback
- Radio: 2 large groups (국내, 해외). Below: recently selected stations ordered newest first, persisted locally (up to 8). Domestic has 6 stations (89.1 KBS CoolFM, 91.9 MBC FM4U, 93.9 CBS MusicFM, 95.9 MBC 표준FM, 102.7 AFN EagleFM, 107.7 SBS PowerFM); overseas has 2 (102.7 KIIS-FM LA, Gallery 41). Station icons reuse the original project's StationArt.java implementation.
- Streaming: 2 large groups (플레이리스트, 다운로드); below are 10 **clearly marked sample** liked-track rows to test long-scroll and host rendering
- CAN: intentionally empty
- Radio station selection updates the history and Home label, but **NO radio audio** is played
- Streaming items are placeholders only; do not read the actual likes, playlists, or downloads of the user's music service
- Does not access Bluetooth, CAN, OBD, or network. No audio output

Android Auto host controls rendering. A photo of the v01 Android Auto display demonstrated two group icons with list items below; this version uses that arrangement.

## Build and validation
Android SDK 36 and Java 17. Run `gradle -p aa-ui-probe :app:assembleDebug` and run the emulator `smoke.py` script. It checks package launch and real MediaBrowserService root via in-app browser.
