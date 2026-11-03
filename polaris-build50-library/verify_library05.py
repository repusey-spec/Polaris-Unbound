#!/usr/bin/env python3
"""Source guards complement real Android MediaItem and resolver regression tests."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
base = root / 'app/src/main/kotlin/app/hush/music'
svc = (base / 'playback/MusicService.kt').read_text()
cb = (base / 'playback/MediaLibrarySessionCallback.kt').read_text()
ext = (base / 'extensions/MediaItemExt.kt').read_text()
radio = base / 'polaris'
checks = {
    'persisted metadata has a radio-specific conversion': 'PolarisRadioRequests.queueItem' in ext,
    'service direct radio restore bypasses music hydration': 'radio-persistent-restore queue=' in svc and 'restoredRadio.toMediaItem()' in svc,
    'callback validates existing radio configuration': 'PolarisRadioRequests.normalize(item)' in cb,
    'HTTP radio factory resolves internal requests first': 'ResolvingDataSource.Factory(radioHttp)' in svc and 'PolarisRadioRequests.resolve(spec)' in svc,
    'MediaSource boundary repairs old radio URIs': 'selected.createMediaSource(playable)' in (radio / 'PolarisSourceFactory.kt').read_text(),
    'AA set and resumption return complete queues': 'radio-set queue=' in cb and 'radio-resume queue=' in cb,
    'phone selection builds complete queue': 'PolarisRadioPlaylist.set(player, context, item)' in (radio / 'PolarisRadioScreen.kt').read_text(),
    'recovery replaces source without deleting queue': 'player.setMediaItem(' not in (radio / 'PolarisRadioRecovery.kt').read_text() and 'player.replaceMediaItem(' in (radio / 'PolarisRadioRecovery.kt').read_text(),
    'reconnect builds complete queue': 'PolarisRadioPlaylist.set(' in (radio / 'PolarisProjectionRecovery.kt').read_text(),
    'hardware skip and direct voice keep complete queues': 'player.setMediaItem(radioItem)' not in svc and 'PolarisRadioPlaylist.set(player, this@MusicService, radioItem)' in svc and 'PolarisRadioPlaylist.set(player, this@MusicService, app.hush.music.polaris.PolarisRadioPlayback.mediaItem(next, url))' in svc,
    'no reentrant Build49 queue mutation helper': not (radio / 'PolarisRadioQueue.kt').exists() and 'polarisRadioQueue.onTransition' not in svc,
    'music source toggle skips live radio': 'mediaId.startsWith("polaris:station:") || index < 0' in svc,
    'native AA search supported without external assistant': 'android.media.browse.SEARCH_SUPPORTED' in cb and 'VOICE_ASSIST' not in cb and 'ACTION_ASSIST' not in cb and 'polarisVoiceSearchItem' not in cb,
    'original YouTube Music search still handles requests': '.search(query, YouTube.SearchFilter.FILTER_SONG)' in cb and 'notifySearchResultChanged(browser, query, count, params)' in cb,
    'original music sources and playlists retained': 'PolarisSourceFactory(music, radio)' in svc and 'private suspend fun playlistChildren(' in cb,
    'reports retained and include queue counts': 'PolarisErrorReport.failure("player", error, player.currentMediaItem)' in svc and 'radio-set queue=' in cb,
    'same package and next version': 'versionCode = 100567' in (root / 'app/build.gradle.kts').read_text() and 'versionName = "50.5"' in (root / 'app/build.gradle.kts').read_text(),
}
for name in ['small_icon', 'app_icon_small', 'app_icon_monochrome']:
    icon = ET.parse(root / 'app/src/main/res/drawable' / (name + '.xml')).getroot()
    checks[name + ' is a 24dp vector, not centered bitmap'] = icon.tag == 'vector' and icon.get('{http://schemas.android.com/apk/res/android}width') == '24dp'
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + name)
assert all(checks.values())
