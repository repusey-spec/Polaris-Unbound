#!/usr/bin/env python3
"""Build 53 structural regression gates for Work/AA integration."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
src=root/"app/src/main/kotlin/app/hush/music"
service=(src/"playback/MusicService.kt").read_text()
callback=(src/"playback/MediaLibrarySessionCallback.kt").read_text()
radio=(src/"polaris/PolarisRadioScreen.kt").read_text()
builder=(src/"polaris/PolarisRadioQueueBuilder.kt").read_text()
catalog=(src/"polaris/RadioCatalog.kt").read_text()
metadata=(src/"polaris/PolarisRadioMetadata.kt").read_text()
checks={
  "AA now-playing queue created before player receives items": "PolarisRadioQueueBuilder" in callback and "queue.items, queue.index, 0L" in callback,
  "Android Auto playback queue has real URIs not fake": "polaris.invalid" not in builder and 'PolarisRadioPlayback.resolveUrl(id)' in builder,
  "all thirteen station presets retained": catalog.count('Station("') == 13,
  "station order derived from user's slot layout": "PolarisRadioLayout(context).slots()" in builder,
  "no reentrant radio queue changes": "polarisRadioQueue.onTransition" not in service and "PolarisRadioQueue.kt" not in str(list((src/"polaris").glob("*.kt"))),
  "phone radio uses full validated queue": "player.setMediaItems(queue.items, queue.index, 0L)" in radio,
  "radio skip uses full validated queue": "player.setMediaItems(nextQueue.items, nextQueue.index, 0L)" in service,
  "voice radio uses full validated queue": "player.setMediaItems(radioQueue.items, radioQueue.index, 0L)" in service,
  "remove giant AA Radio shortcut from Streaming": '"polaris:radio", "Radio", "라디오 채널 선택"' not in callback,
  "remove giant AA Streaming shortcut from Radio": '"음악 추천과 보관함"' not in callback,
  "mode switch exposed via media button preferences": "mediaSession.setMediaButtonPreferences(customLayout)" in service,
  "music queue / position resume preserved": "polarisSavedStreamingQueue" in service and "polarisSavedStreamingPosition" in service,
  "online playlist reconciliation preserved": "polarisPlaylistMediaItems" in callback,
  "artwork persists via content URI": "setArtworkUri(PolarisRadioArtwork.uri(id))" in metadata,
}
for name,ok in checks.items():
    print(("PASS" if ok else "FAIL")+": "+name)
assert all(checks.values()), "Build53 regression failure"
