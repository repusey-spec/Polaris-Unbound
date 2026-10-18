# YT Music Test 04 → next diagnostic source review (no APK built)

## Evidence from real phone screenshots

1. `HTTP 200` and `visitor=true`: the anonymous WEB_REMIX request received a server response and a public visitor value. This **does not** prove it contained music recommendations.
2. `contentsKeys=[singleColumnBrowseResultsRenderer]`, `sectionNodes=1`, `shelfTypes=0`: the Test 04 parser saw only one Home section node and found none of its supported direct shelf renderers. The actual type of that node is **not yet observed**.
3. The phone reports `Library: 0` while both the opt-in accessibility service and notification listener are enabled. This does not establish whether the accessibility service received YTM screen nodes or the label classification failed.
4. There is **no proof** that the public, anonymous Home request can return the user's *personalized* YTM Home, even if ordinary public recommendations work.

## Source-only changes made after Test 04

- The existing diagnostic-source branch already adds structural `nodeTypes` reporting for the first Home response node; this revision also reads genuine nested `itemSectionRenderer` children instead of only first-level shelves. It recognizes `musicCarouselShelfRenderer`, `musicShelfRenderer`, `gridRenderer`, `musicCardShelfRenderer`, uses a real title or a real `strapline`, and handles `contents` or `items` arrays. An empty response stays empty; no fake playlists are fabricated.
- On an empty Library result, the phone shows the last opt-in YTM accessibility scan time, node count, text count, metadata candidate count, and whether any real entries were stored.
- The accessibility collector examines visible YTM text and content descriptions, accepts alternative punctuation, and reports zero-result captures. It does not operate on other app packages or extract account cookies.
- The original media relay, NAS/radio/CAN pages and official YTM playback handoff remain unchanged.

## Next decision from actual device evidence

- **Home response contains `itemSectionRenderer` with nested music shelves:** now attempt to show the real shelves; if no items, inspect next nested renderer type.
- **Home response contains `messageRenderer` / signed-out prompt and no media IDs:** treat as no accessible anonymous recommendations rather than a parsing bug. A private-account Home requires a different authorized route.
- **Library scan counts > 0 but matches = 0:** update labels parser using actual redacted AccessibilityNodeInfo structure, not further permission prompts.
- **Library has no recent scan:** inspect whether official YTM was in the foreground and whether the service can retrieve the active root.

### Checkpoint

Changes were saved to branch `experiment/ytm-home-library-diagnostics-source` only.
No CI APK build, new version code, installation, or merge was performed at this step.
Stable Polaris v0.58 remains untouched.
