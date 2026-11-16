#!/usr/bin/env python3
"""Polaris 6.07 release guard: phone mini bar vectors + 6.06 functionality intact."""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
code=(root/"app/src/main/java/com/dd3boh/outertune/polaris/PolarisShell.kt").read_text(encoding="utf-8")
build=(root/"app/build.gradle.kts").read_text(encoding="utf-8")
radio=code.split("fun PolarisRadioMini(",1)
assert len(radio)==2, "Radio mini UI missing"
mini=radio[1]
assert 'versionCode = 100607' in build and 'versionName = "6.07"' in build
assert 'applicationId = "app.hush.music.polarisradio.debug"' in build
assert mini.count('IconButton(')==3, "Mini controls must have exactly three equal buttons"
assert mini.count('modifier = Modifier.size(40.dp)')==3, "Button touch sizes not equal"
assert mini.count('modifier = Modifier.size(24.dp)')==3, "Drawable sizes not equal"
for name in ['skip_previous','skip_next','pause','play']:
    assert 'R.drawable.'+name in mini, f'Missing actual vector drawable {name}'
for glyph in ['Text("◀")','"Ⅱ"','Text("▶▶")','"▶"']:
    assert glyph not in mini, f'Old text-based control remains: {glyph}'
for command in ['seekToPreviousMediaItem()','seekToNextMediaItem()',
                'connection.player.pause()','connection.player.prepare(); connection.player.play()']:
    assert command in mini, f'Existing button behavior removed: {command}'
# Core engine, AA playlist routing, legacy radio programme clocks and remembered
# last source are still the same validated 6.06 implementation.
cb=(root/"app/src/main/java/com/dd3boh/outertune/playback/MediaLibrarySessionCallback.kt").read_text()
assert 'RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()' in cb
assert 'MusicService.PLAYLIST -> polarisPlaylistRows()' in cb
service=(root/"app/src/main/java/com/dd3boh/outertune/playback/MusicService.kt").read_text()
assert 'PolarisLastSource.radioToResume(this@MusicService)' in service
assert 'PolarisRadioBrowsePrograms(this, scope)' in service
assert 'PolarisRadioMetadata.title(id)' in cb
assert (root/"app/src/main/java/com/dd3boh/outertune/polaris/PolarisRadioFullPlayer.kt").exists()
assert 'intertune603-song.db' in (root/"app/src/main/java/com/dd3boh/outertune/db/MusicDatabase.kt").read_text()
print("PASS: 6.07 three symmetric real vector mini controls; 6.06 features and data namespace preserved")
