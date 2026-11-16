#!/usr/bin/env python3
"""6.08 regression guard: AA browse appearance only, 6.07 playback intact."""
from pathlib import Path
import sys,subprocess
root=Path(sys.argv[1]).resolve()
p=root/"app/src/main/java/com/dd3boh/outertune"
s=(p/"playback/MediaLibrarySessionCallback.kt").read_text()
app=(root/"app/build.gradle.kts").read_text()
provider=(p/"polaris/PolarisRadioArtworkProvider.kt").read_text()
tile=(p/"polaris/PolarisRadioBrowseTile.kt").read_text()
assert 'versionCode = 100608' in app and 'versionName = "6.08"' in app
assert 'applicationId = "app.hush.music.polarisradio.debug"' in app
assert 'RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)' in s
assert 'RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()' not in s
assert 'MusicService.PLAYLIST -> polarisPlaylistRows()' in s
assert 'CarSearch.page(icons + rows, page, pageSize)' in s
assert 'style(single = category)' in s
assert 'MusicService.SONG, context.getString(R.string.songs)' in s
assert 'MusicService.ARTIST, context.getString(R.string.artists)' in s
assert 'MusicService.ALBUM, context.getString(R.string.albums)' in s
assert 'MusicService.PLAYLIST, context.getString(R.string.playlists)' in s
assert 'it.toMediaItem(MusicService.SONG)' in s
assert 'it.toMediaItem(MusicService.PLAYLIST + "/" + PlaylistEntity.LIKED_PLAYLIST_ID)' in s
assert 'PolarisRadioArtwork.browseTileUri(id, station.title, live)' in s
assert 'PolarisRadioMetadata.title(id)' in s
assert 'Paint.Align.CENTER' in tile and 'Layout.Alignment.ALIGN_CENTER' in tile
assert 'setMaxLines(2)' in tile
assert 'tileId(uri)' in provider and 'PolarisRadioBrowseTile.render(ctx, stationId)' in provider
assert 'StationArt.bitmap(ctx, stationId, 256)' in provider, "Normal radio artwork path changed"
assert 'PolarisLastSource.radioToResume(this@MusicService)' in (p/"playback/MusicService.kt").read_text()
assert 'PolarisRadioBrowsePrograms(this, scope)' in (p/"playback/MusicService.kt").read_text()
mini=(p/"polaris/PolarisShell.kt").read_text().split("fun PolarisRadioMini(",1)[1]
assert mini.count("IconButton(")==3 and "R.drawable.skip_previous" in mini and "R.drawable.skip_next" in mini
for rel in ["utils/StreamCheck.kt","utils/StreamOrder.kt","utils/potoken/PoTokenGenerator.kt",
            "playback/StandIns.kt","playback/BadCachedCopy.kt"]:
    path=p/rel
    original=subprocess.check_output(["git","-C",str(root),"show","HEAD:"+str(path.relative_to(root))])
    assert path.read_bytes()==original,rel
assert 'intertune603-song.db' in (p/"db/MusicDatabase.kt").read_text()
assert 'intertune603-settings' in (p/"utils/DataStore.kt").read_text()
print("PASS: 6.08 AA categories + playable song rows; centered dynamic radio browse artwork")
print("PASS: 6.07 radio/music engine, source restore, liked lists, mini player and persistent data intact")
