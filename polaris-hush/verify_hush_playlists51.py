#!/usr/bin/env python3
"""Build 51 additional structural guards, run after staged overlays."""
from pathlib import Path
import sys
src=Path(sys.argv[1])/"app/src/main/kotlin/app/hush/music"
service=(src/"playback/MusicService.kt").read_text()
callback=(src/"playback/MediaLibrarySessionCallback.kt").read_text()
screen=(src/"polaris/PolarisRadioScreen.kt").read_text()
gate=(src/"polaris/PolarisPlaybackRequestGate.kt").read_text()
checks={
    "playlists use hybrid local/online queue": "polarisPlaylistMediaItems(playlistId, sortOption)" in callback or "polarisPlaylistMediaItems(" in callback,
    "downloaded and liked retain local route": "playlistId == PlaylistEntity.LIKED_PLAYLIST_ID" in callback and "playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID" in callback,
    "remote playlist cache available": "polarisRemotePlaylistCache" in callback,
    "AA playlist selection uses real playable items": "val playable = polarisPlaylistMediaItems(" in callback,
    "empty playlist does not blank active playback": "playlist resolved 0 songs; kept previous playback" in callback,
    "voice request gate before async lookup": "val polarisRequest = app.hush.music.polaris.PolarisPlaybackRequestGate.begin()" in service,
    "voice results guarded": service.count("PolarisPlaybackRequestGate.isCurrent(polarisRequest)") >= 2,
    "phone radio requests guarded": "PolarisPlaybackRequestGate.isCurrent(polarisRequest)" in screen,
    "user pause invalidates pending playback request": "if (!playWhenReady)" in service and "PolarisPlaybackRequestGate.cancel()" in service,
    "music mode switch preserves full queue": "polarisSavedStreamingQueue" in service and "startIndex = polarisSavedStreamingIndex" in service,
    "radio mutation regression still absent": "polarisRadioQueue" not in service,
    "no fake URL class compiled": not (src/"polaris/PolarisRadioQueue.kt").exists(),
    "request gate uses AtomicLong": "AtomicLong" in gate,
}
for name,yes in checks.items():
    print(("PASS" if yes else "FAIL")+": "+name)
assert all(checks.values()), "Build 51 structural checks failed"
