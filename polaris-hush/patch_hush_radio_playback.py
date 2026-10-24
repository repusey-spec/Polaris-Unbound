#!/usr/bin/env python3
"""Second-stage patch: route direct-stream stations through Hush's existing player.
Requires patch_hush_browse.py first. Korean API-resolved stations are excluded
until the Polaris resolver is ported; no fake audio URLs.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1])/"app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
s=p.read_text()
needle='''                        "polaris:radio" -> {
                            // Radio station playback is NOT connected yet.
                            // Avoid advertising unplayable stations to AA.
                            emptyList()
                        }'''
assert s.count(needle)==1
s=s.replace(needle,'''                        "polaris:radio" -> {
                            PolarisRadioStreams.streams.map { station ->
                                MediaItem.Builder()
                                    .setMediaId("polaris:station:" + station.id)
                                    .setUri(station.url)
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
                        }''',1)
needle='''                val voiceQuery =
                    firstItem.requestMetadata.searchQuery'''
assert s.count(needle)==1
s=s.replace(needle,'''                val polarisStation =
                    PolarisRadioStreams.streams.firstOrNull {
                        firstItem.mediaId == "polaris:station:" + it.id
                    }
                if (polarisStation != null) {
                    val radioItem = MediaItem.Builder()
                        .setMediaId("polaris:station:" + polarisStation.id)
                        .setUri(polarisStation.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(polarisStation.title)
                                .setArtist("Polaris Radio")
                                .setIsPlayable(true)
                                .build()
                        ).build()
                    return@future MediaSession.MediaItemsWithStartPosition(
                        listOf(radioItem), 0, 0L
                    )
                }
                val voiceQuery =
                    firstItem.requestMetadata.searchQuery''',1)
s=s.replace('package app.hush.music.playback\n','package app.hush.music.playback\n\nimport app.hush.music.polaris.PolarisRadioStreams\n',1)
p.write_text(s)
print("Direct radio items routed through existing Hush MediaSession")
