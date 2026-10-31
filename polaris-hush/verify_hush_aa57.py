#!/usr/bin/env python3
"""Build57 source contract tests against fully patched pinned Hush."""
from pathlib import Path
import sys
root=Path(sys.argv[1])/"app/src/main/kotlin/app/hush/music"
cb=(root/"playback/MediaLibrarySessionCallback.kt").read_text()
svc=(root/"playback/MusicService.kt").read_text()
checks={
    "AA Radio folder is retrievable from onGetItem":'mediaId == "polaris:radio" -> {' in cb,
    "AA station item is retrievable from onGetItem":'mediaId.startsWith("polaris:station:") -> {' in cb,
    "radio browse and getItem use the same media ID":'setMediaId("polaris:station:" + station.id)' in cb and 'removePrefix("polaris:station:")' in cb,
    "streaming playlist root still published":'when (if (parentId == "polaris:streaming") MusicService.PLAYLIST else parentId)' in cb,
    "root playlist rows browse-only":'parentId == "polaris:streaming" || parentId == MusicService.PLAYLIST' in cb and '.setIsPlayable(false)' in cb,
    "getItem playlist folders likewise browse-only":'val visibleItem =' in cb and 'mediaId.count { ch -> ch == \'/\' } == 1' in cb,
    "Radio add-to-queue resolves live stream":'AA station add failed=' in cb and 'listOf(PolarisRadioPlayback.mediaItem(stationId, resolved))' in cb,
    "radio-to-streaming fallback is playable":'internal suspend fun firstPolarisStreamingItem()' in cb and '?.toMediaItem(MusicService.HOME_QUICK_PICKS)' not in cb,
    "radio switch builds queue with valid URIs":'PolarisRadioQueueBuilder.build(' in svc and 'player.setMediaItems(radioQueue.items, radioQueue.index, 0L)' in svc,
    "Radio and Streaming custom command remains":'setDisplayName("Streaming")' in svc and 'setDisplayName("Radio")' in svc,
    "previously requested Polaris branding remains":(root.parents[3]/"res/drawable/small_icon.xml").exists(),
}
for label,ok in checks.items():
    print(("PASS" if ok else "FAIL")+": "+label)
assert all(checks.values()), "Build57 media navigation contract failed"
