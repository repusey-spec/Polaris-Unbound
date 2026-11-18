#!/usr/bin/env python3
"""Guard the 6.06 AA browse patch and 6.04 stream/radio invariants."""
from pathlib import Path
import sys, subprocess

p = Path(sys.argv[1]).resolve()
root = p / "app/src/main/java/com/dd3boh/outertune"
g = (p/"app/build.gradle.kts").read_text()
s = (root/"playback/MediaLibrarySessionCallback.kt").read_text()
assert 'versionCode = 100606' in g and 'versionName = "6.06"' in g
assert 'applicationId = "app.hush.music.polarisradio.debug"' in g
for snippet in [
    'YouTube.playlist("LM")',
    'YouTube.playlistContinuation(token)',
    'val likedSongCount = playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID).size',
    'val downloadedSongCount = playlistSongs(PlaylistEntity.DOWNLOADED_PLAYLIST_ID).size',
    'PlaylistEntity.LIKED_PLAYLIST_ID -> carLikedSongs()',
    'PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs().first()',
    'is PlayRequest.Playlist -> playlistSongs(target.playlistId)',
    'return previous?.songs ?: local',
    'items.distinctBy { it.id }',
    'carLikedMutex.unlock()',
]:
    assert snippet in s, snippet
assert 'RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()' in s
assert 'MusicService.PLAYLIST -> polarisPlaylistRows()' in s
assert 'PolarisLastSource.radioToResume(context)' in s
assert 'PolarisLastSource.radioToResume(this@MusicService)' in (root/'playback/MusicService.kt').read_text()
assert 'PolarisLastSource.rememberAudible(context' in (root/'polaris/PolarisRadioSession.kt').read_text()
assert 'private fun polarisRadioTile(id: String)' in s
assert 'val likedSongCount = database.likedSongsCount().first()' not in s
assert 'val downloadedSongCount = downloadUtil.downloads.value.size' not in s
for rel in ["utils/StreamCheck.kt", "utils/StreamOrder.kt",
            "utils/potoken/PoTokenGenerator.kt", "playback/StandIns.kt",
            "playback/BadCachedCopy.kt"]:
    src = root / rel
    original = subprocess.check_output(["git", "-C", str(p), "show", "HEAD:" + str(src.relative_to(p))])
    assert src.read_bytes() == original, rel
assert 'HlsMediaSource.Factory(radioDataSources)' in (root/"polaris/PolarisMediaSources.kt").read_text()
assert 'noteWebStreamRejected' in (root/"utils/YTPlayerUtils.kt").read_text()
assert 'intertune603-song.db' in (root/"db/MusicDatabase.kt").read_text()
assert 'intertune603-settings' in (root/"utils/DataStore.kt").read_text()
print("PASS: 605 same-source browse+playback, no changes to 604 engine, radio or data paths")
