#!/usr/bin/env python3
"""Build 50 static source regression checks after all Hush overlays are applied.

These are structural guards, NOT a substitute for on-car audio testing.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/kotlin/app/hush/music"
service = (base / "playback/MusicService.kt").read_text()
callback = (base / "playback/MediaLibrarySessionCallback.kt").read_text()
radio = (base / "polaris/PolarisRadioScreen.kt").read_text()
playback = (base / "polaris/PolarisRadioPlayback.kt").read_text()
metadata = (base / "polaris/PolarisRadioMetadata.kt").read_text()
provider = (base / "polaris/PolarisRadioArtworkProvider.kt").read_text()
workflow_manifest = (root / "app/src/main/AndroidManifest.xml").read_text()

checks = {
    "no nested Build49 queue injection": "polarisRadioQueue.onTransition(" not in service,
    "no queue helper installed": "private val polarisRadioQueue" not in service,
    "unsafe queue class absent from APK source": not (base / "polaris/PolarisRadioQueue.kt").exists(),
    "radio uses prebuilt Media3 queue, not callback mutation": "player.setMediaItems(queue.items, queue.index, 0L)" in radio,
    "radio queue resolves genuine broadcaster URLs": "PolarisRadioPlayback.resolveUrl(selectedId)" in (base / "polaris/PolarisRadioQueueBuilder.kt").read_text(),
    "radio playback sets live station URI": '.setUri(url)' in playback,
    "AA radio browse retains 13 presets": 'PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find)' in callback,
    "AA streaming still has thumbnail picks": 'val picks = database.quickPicks().first().take(12)' in callback,
    "AA Radio and Streaming top roots retained": '"polaris:radio"' in callback and '"polaris:streaming"' in callback,
    "AA playback switch actions retained": 'setDisplayName("Streaming")' in service and 'setDisplayName("Radio")' in service,
    "AA radio hides like-repeat-shuffle-add": 'if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true)' in service,
    "AA artwork URI still installed": 'setArtworkUri(PolarisRadioArtwork.uri(id))' in metadata,
    "AA artwork provider still installed": 'class PolarisRadioArtworkProvider' in provider and 'PolarisRadioArtworkProvider' in workflow_manifest,
    "no fake URLs in active radio playback": "polaris.invalid" not in playback,
}
for title, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + title)
assert all(checks.values()), "Build 50 static safety check failed"
