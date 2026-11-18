#!/usr/bin/env python3
"""Gate 6.13 on real production code and preservation of the media engine."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune"
callback=(src/"playback/MediaLibrarySessionCallback.kt").read_text()
nav=(src/"polaris/Polaris613Nav.kt").read_text()
auto=(src/"playback/AutoCar.kt").read_text()
manifest=(root/"app/src/main/AndroidManifest.xml").read_text()
gradle=(root/"app/build.gradle.kts").read_text()
checks={
    "four root tabs":all(x in callback for x in [
        'browsableMediaItem(Polaris613Nav.HOME',
        'browsableMediaItem(Polaris613Nav.RADIO',
        'browsableMediaItem(Polaris613Nav.STREAM',
        'browsableMediaItem(Polaris613Nav.CAN']),
    "radio original art + programme one-line":'.setArtworkUri(PolarisRadioArtwork.uri(id))' in callback and 'cached.program' in callback and '.setSubtitle(null).setArtist(null)' in callback,
    "radio recent durable":'fun remember(context:Context,id:String)' in nav and 'Polaris613Nav.remember(context,id)' in callback,
    "stream actual likes":'carLikedSongs()' in callback and 'playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID)' in callback,
    "stream two compact groups":'Polaris613Nav.DOWNLOADS' in callback and 'val categories = items.take(2)' in auto,
    "CAN no data":'Polaris613Nav.CAN -> emptyList()' in callback,
    "media engine intact":(src/"playback/MusicService.kt").exists() and (src/"polaris/PolarisRadioPlayback.kt").exists(),
    "correct identity/version":'applicationId = "app.hush.music.polarisradio.debug"' in gradle and 'versionCode = 100613' in gradle,
    "CAL four tabs":"val known = tabs?.take(4)" in auto,
    "icon provider":'Polaris613ArtProvider' in manifest and 'polaris.menuart' in manifest
}
for k,v in checks.items():print(('PASS' if v else 'FAIL'),k)
assert all(checks.values()),"6.13 source gates did not pass"
