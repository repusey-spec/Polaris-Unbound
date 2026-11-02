#!/usr/bin/env python3
"""Structural guards only; runtime policy/parser tests run separately under JUnit."""
from pathlib import Path
import sys
root=Path(sys.argv[1]); base=root/'app/src/main/kotlin/app/hush/music'
svc=(base/'playback/MusicService.kt').read_text()
cb=(base/'playback/MediaLibrarySessionCallback.kt').read_text()
checks={
    'no process-global byte routing': 'PolarisRadioNetwork' not in svc and 'PolarisRadioRoutingDataSource' not in svc,
    'source-bound radio and original Hush factories': 'PolarisSourceFactory(music, radio)' in svc and 'resolvePlaybackDataSpec(dataSpec = dataSpec)' in svc,
    'player cause chain persisted for both sources': 'PolarisErrorReport.failure("player", error, player.currentMediaItem)' in svc,
    'AA add and set normalize playable items': 'listOf(polarisPlaybackItem(item))' in cb and 'result.mediaItems.map { polarisPlaybackItem(it) }' in cb,
    'persist actual returned browse count': 'returned=" + returnedItems.size' in cb,
    'original Hush queues and search retained': 'private suspend fun playlistChildren(' in cb and 'searchOnlineSongs(' in cb,
    'no reciprocal source action restored': 'switchPolarisMode' not in svc and 'PolarisAAModeCommand' not in cb,
    'two phone report entrypoints': 'PolarisErrorReport.open(context)' in (base/'ui/screens/settings/SettingsScreen.kt').read_text() and 'PolarisErrorReport.open(context)' in (base/'polaris/PolarisRadioScreen.kt').read_text(),
    'same identity and next version': 'versionCode = 100566' in (root/'app/build.gradle.kts').read_text() and 'versionName = "50.4"' in (root/'app/build.gradle.kts').read_text(),
}
for name,ok in checks.items(): print(('PASS' if ok else 'FAIL')+': '+name)
assert all(checks.values())
