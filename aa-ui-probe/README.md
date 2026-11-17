# Polaris Android Auto UI Probe 0.1 — UI ONLY

Standalone, separately installable application. Does **not** replace or update Polaris Unbound. Package: dev.polaris.aauiprobe. The app never accesses CAN, Bluetooth, OBD, network or real audio.

## Navigation
| Top Android Auto root tab | Second level | Preview below |
| --- | --- | --- |
| Home | No second icon grid | CAN + Radio and CAN + Streaming summaries |
| CAN | 서플라이, 전비기록, 상태, 로깅 | Mock status and log rows |
| Radio | 국내, 해외 | Mock station rows |
| Streaming | 좋아요, 저장, 추천, 최근재생 | Mock music rows |

The **phone app** draws the intended layout. Android Auto is host-rendered from MediaBrowserService. Category grid hints apply to the submenus; list hints apply to preview rows, but the actual host may ignore, rearrange, or vertically render them. This is the purpose of the test.

## Instructions
1. Install the APK alongside existing Polaris; the separate package does not overwrite Polaris.
2. Open Polaris UI Probe on the phone first; verify all four tabs and mock menus.
3. Tap the in-phone diagnostic button and check for PASS with 4 root tabs, CAN 4, Radio 2, Streaming 4.
4. Connect Android Auto, open Polaris UI Probe (developer Unknown sources for media apps may be required).
5. Photograph Home, CAN, Radio and Streaming screens. The host, not the app, decides whether a second grid is displayed.

No audio or vehicle telemetry is ever generated, recorded or sent.

## Implementation
Native android.service.media.MediaBrowserService, Android Auto media-only discovery, four browsable root children, per-item category-grid hints for second-level menu and list hints for preview rows. Phone self-test connects to actual MediaBrowser tree.

Gradle build: gradle -p aa-ui-probe :app:assembleDebug
