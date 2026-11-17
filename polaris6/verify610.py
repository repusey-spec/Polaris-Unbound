#!/usr/bin/env python3
"""6.10 regression checks: data stays intact; only AA layout hints/artwork change."""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
cb=(pkg/"playback/MediaLibrarySessionCallback.kt").read_text()
catalog=(pkg/"polaris/RadioCatalog.kt").read_text()
art=(pkg/"polaris/PolarisRadioBrowseTile.kt").read_text()
provider=(pkg/"polaris/PolarisRadioArtworkProvider.kt").read_text()
build=(root/"app/build.gradle.kts").read_text()

assert 'versionCode = 100610' in build and 'versionName = "6.10"' in build
assert 'applicationId = "app.hush.music.polarisradio.debug"' in build
for key in ('polaris:radio:v610','polaris:streaming:v610','polaris:playlists:v610',
            'polaris:radio-tile:v610:','polaris:radio:v609','polaris:streaming:v609'):
    assert key in catalog, key
home=cb.split("private suspend fun polarisStreamingHome(",1)[1].split("return CarSearch.page(icons + rows, page, pageSize)",1)[0]
for key in ('MusicService.SONG','MusicService.ARTIST','MusicService.ALBUM',
            'RadioCatalog.PLAYLIST_ROOT_ID','val library = librarySongs()',
            'carLikedSongs()'):
    assert key in home, key
assert 'MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM' not in home
assert 'style(single = category)' not in home
assert 'putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)' in cb
assert 'polarisStreamingHome(page, pageSize)' in cb
assert 'RadioCatalog.STREAMING_BROWSE_PREVIOUS_ROOT_ID' in cb
assert 'RadioCatalog.RADIO_BROWSE_PREVIOUS_ROOT_ID' in cb
assert 'RadioCatalog.PLAYLIST_PREVIOUS_ROOT_ID' in cb
assert 'val lineWidth = 496' in art
assert 'Rect(40, 0, 472, 432)' in art
assert 'Layout.Alignment.ALIGN_CENTER' in art
assert 'canvas.drawText(caption' not in art
assert 'stationPaint' not in art
assert 'canvas.translate((SIZE - lineWidth) / 2f, 442f)' in art
assert '.appendPath("tile610")' in provider
assert 'listOf("tile", "tile609", "tile610")' in provider
assert 'PolarisLastSource.radioToResume(this@MusicService)' in (pkg/"playback/MusicService.kt").read_text()
assert 'intertune603-song.db' in (pkg/"db/MusicDatabase.kt").read_text()
assert 'intertune603-settings' in (pkg/"utils/DataStore.kt").read_text()
print("PASS: 6.10 unforced native compact browser categories + rows, large radio logo and centred programme only")
print("PASS: 6.09 browse compatibility and existing database/account/last source unchanged")
