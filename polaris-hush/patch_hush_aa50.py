#!/usr/bin/env python3
"""Build 50 regression fix for Build 49 radio crashes and silent playback.

Build 49 modified Media3's timeline reentrantly from onMediaItemTransition and
inserted fake URLs for unresolved stations. Restore Build 48's stable one-item
live radio playback, retaining Build 49 streaming AA browse and mode-switch UI.

Apply strictly AFTER patch_hush_aa49.py; intentionally no APK or stable changes.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
service = root / "app/src/main/kotlin/app/hush/music/playback/MusicService.kt"
source = service.read_text()

def replace_exact(old: str, new: str, label: str):
    global source
    count = source.count(old)
    assert count == 1, f"{label}: expected one source anchor, found {count}"
    source = source.replace(old, new, 1)

replace_exact(
    '''    private val polarisRadioQueue by lazy {
        app.hush.music.polaris.PolarisRadioQueue(this, player, scope)
    }
''',
    "",
    "remove Build49 queue mutation helper"
)

replace_exact(
    '''        polarisProjectionRecovery.onMediaItemTransition(mediaItem)
        polarisRadioQueue.onTransition(mediaItem)
''',
    '''        polarisProjectionRecovery.onMediaItemTransition(mediaItem)
''',
    "restore stable Build48 transition callback"
)

# Preserve Build49 radio/streaming memory, custom buttons, and Streaming home.
assert "private fun switchPolarisMode()" in source
assert "polarisLastRadioId" in source
assert 'setDisplayName("Streaming")' in source
assert 'setDisplayName("Radio")' in source
assert "polarisRadioQueue" not in source
service.write_text(source)
print("Build 50: restored single station playback; no reentrant queue edits")
