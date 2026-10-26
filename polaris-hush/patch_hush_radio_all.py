#!/usr/bin/env python3
"""Complete radio callback patch for pinned Hush, after browse patch."""
from pathlib import Path
import sys
p = Path(sys.argv[1]) / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
s = p.read_text()
old = '''                        "polaris:radio" -> {
                            // Radio station playback is NOT connected yet.
                            // Avoid advertising unplayable stations to AA.
                            emptyList()
                        }'''
new = '''                        "polaris:radio" -> {
                            PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find).map { station ->
                                MediaItem.Builder()
                                    .setMediaId("polaris:station:" + station.id)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(station.title)
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .setExtras(playableExtras())
                                            .build()
                                    ).build()
                            }
                        }'''
assert s.count(old) == 1
s = s.replace(old, new)
anchor = '''                val voiceQuery =
                    firstItem.requestMetadata.searchQuery'''
assert s.count(anchor) == 1
insert = '''                val radioStation = RadioCatalog.stations.firstOrNull {
                    firstItem.mediaId == "polaris:station:" + it.id
                }
                if (radioStation != null) {
                    val url = PolarisRadioPlayback.resolveUrl(radioStation.id)
                    val item = PolarisRadioPlayback.mediaItem(radioStation.id, url)
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
                }
'''
s = s.replace(anchor, insert + anchor, 1)
pkg = "package app.hush.music.playback\n"
assert s.count(pkg) == 1
s = s.replace(pkg, pkg + """
import app.hush.music.polaris.RadioCatalog
import app.hush.music.polaris.PolarisRadioLayout
import app.hush.music.polaris.PolarisRadioStreams
import app.hush.music.polaris.PolarisRadioResolver
import app.hush.music.polaris.PolarisRadioPlayback
""", 1)
p.write_text(s)
print("Patched 13 stations: dynamic URL resolution on selection, shared Hush MediaSession")
