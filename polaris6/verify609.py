#!/usr/bin/env python3
"""Polaris 6.09 regression guards: both real AA routes, versioned radio artwork, unchanged core."""
from pathlib import Path
import sys,subprocess
root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
tree=(pkg/"playback/MediaLibrarySessionCallback.kt").read_text()
catalog=(pkg/"polaris/RadioCatalog.kt").read_text()
provider=(pkg/"polaris/PolarisRadioArtworkProvider.kt").read_text()
art=(pkg/"polaris/PolarisRadioBrowseTile.kt").read_text()
build=(root/"app/build.gradle.kts").read_text()
assert 'versionCode = 100609' in build and 'versionName = "6.09"' in build
assert 'applicationId = "app.hush.music.polarisradio.debug"' in build
assert 'const val RADIO_ROOT_ID = "polaris:radio:v609"' in catalog
assert 'const val STREAMING_ROOT_ID = "polaris:streaming:v609"' in catalog
assert 'const val PLAYLIST_ROOT_ID = "polaris:playlists:v609"' in catalog
assert 'const val RADIO_TILE_PREFIX = "polaris:radio-tile:v609:"' in catalog
for phrase in [
    'RadioCatalog.RADIO_ROOT_ID, "polaris:radio"',
    'RadioCatalog.STREAMING_ROOT_ID, "polaris:streaming" -> polarisStreamingHome(page, pageSize)',
    'MusicService.PLAYLIST -> polarisStreamingHome(page, pageSize)',
    'RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)',
    'browsableMediaItem(RadioCatalog.PLAYLIST_ROOT_ID',
    'setMediaId(RadioCatalog.RADIO_TILE_PREFIX + id)',
    'RadioCatalog.tileStationId(mediaId)?.let',
    'RadioCatalog.tileStationId(request.mediaId)',
    'aa609-children parent=',
    'CarSearch.page(icons + rows, page, pageSize)',
]:
    assert phrase in tree, f"Missing AA route: {phrase}"
assert 'MusicService.PLAYLIST -> polarisPlaylistRows()' not in tree
assert '.appendPath("tile609")' in provider
assert 'uri.pathSegments[0] !in listOf("tile", "tile609")' in provider
assert 'PolarisRadioBrowseTile.render(ctx, stationId)' in provider
assert 'aa609-artwork path=' in provider
assert 'Layout.Alignment.ALIGN_CENTER' in art
assert 'Paint.Align.CENTER' in art
assert 'PolarisRadioBrowsePrograms(this, scope)' in (pkg/"playback/MusicService.kt").read_text()
assert 'PolarisLastSource.radioToResume(this@MusicService)' in (pkg/"playback/MusicService.kt").read_text()
assert 'intertune603-song.db' in (pkg/"db/MusicDatabase.kt").read_text()
assert 'intertune603-settings' in (pkg/"utils/DataStore.kt").read_text()
mini=(pkg/"polaris/PolarisShell.kt").read_text().split("fun PolarisRadioMini(",1)[1]
assert mini.count('IconButton(')==3 and 'R.drawable.skip_previous' in mini and 'R.drawable.skip_next' in mini
for rel in ["utils/StreamCheck.kt","utils/StreamOrder.kt","utils/potoken/PoTokenGenerator.kt",
            "playback/StandIns.kt","playback/BadCachedCopy.kt"]:
    p=pkg/rel
    original=subprocess.check_output(["git","-C",str(root),"show","HEAD:"+str(p.relative_to(root))])
    assert p.read_bytes()==original, f"Unexpected changed engine {rel}"
test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisAA609ArtworkTest.kt"
assert test.exists() and 'contentResolver.openInputStream(uri)' in test.read_text()
print("PASS: AA new/old streaming roots and canonical playlist, new radio grid IDs/URI, live source, testable artwork provider")
print("PASS: 6.08 core, source restore, persistent database, 6.07 mini buttons unchanged")
