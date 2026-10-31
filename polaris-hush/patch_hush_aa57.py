#!/usr/bin/env python3
"""Build 57: repair AA browse lookup/play selection, no source-guessing.

Apply after home55 + original AA patches. Does not touch stable Polaris v0.58.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
base=root/"app/src/main/kotlin/app/hush/music"
callback=base/"playback/MediaLibrarySessionCallback.kt"
service=base/"playback/MusicService.kt"
s=callback.read_text()

def once(old,new,why):
    global s
    count=s.count(old)
    assert count==1, f"{why}: expected one anchor, found {count}"
    s=s.replace(old,new,1)

# Radio and its stations were published by onGetChildren, but omitted from
# onGetItem. Some Android Auto hosts issue getItem on tap; ERROR_UNKNOWN can
# reject the source before onSetMediaItems ever has a chance to resolve its URL.
lookup_anchor='''                    mediaId == "polaris:streaming" -> {'''
lookup='''                    mediaId == "polaris:radio" -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                "polaris:radio",
                                "Radio",
                                null,
                                null,
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            null,
                        )
                    }

                    mediaId.startsWith("polaris:station:") -> {
                        val stationId = mediaId.removePrefix("polaris:station:")
                        RadioCatalog.find(stationId)?.let { station ->
                            LibraryResult.ofItem(
                                MediaItem.Builder()
                                    .setMediaId(mediaId)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(station.title)
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .setExtras(playableExtras())
                                            .build()
                                    ).build(),
                                null,
                            )
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

'''
once(lookup_anchor,lookup+lookup_anchor,"Radio folder + station onGetItem lookup")

# Playlists are folders, not buttons for implicit playback. Hush publishes
# them as BOTH playable and browsable; AA may choose play instead of browse.
# Enforce browse-only only at the streaming/library root; preserve song item IDs.
items_anchor='''                LibraryResult.ofItemList(items.paged(page, pageSize), params)'''
items_replacement='''                val visibleItems =
                    if (parentId == "polaris:streaming" || parentId == MusicService.PLAYLIST) {
                        items.map { item ->
                            if (item.mediaId.startsWith(MusicService.PLAYLIST + "/") &&
                                item.mediaId.count { ch -> ch == '/' } == 1) {
                                item.buildUpon().setMediaMetadata(
                                    item.mediaMetadata.buildUpon()
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .build()
                                ).build()
                            } else item
                        }
                    } else items
                LibraryResult.ofItemList(visibleItems.paged(page, pageSize), params)'''
once(items_anchor,items_replacement,"browse-only playlist folder rows")

playlist_lookup='''                        playlistItem(mediaId)?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)'''
playlist_lookup_replacement='''                        playlistItem(mediaId)?.let { item ->
                            val visibleItem =
                                if (mediaId.count { ch -> ch == '/' } == 1) {
                                    item.buildUpon().setMediaMetadata(
                                        item.mediaMetadata.buildUpon()
                                            .setIsPlayable(false)
                                            .setIsBrowsable(true)
                                            .build()
                                    ).build()
                                } else item
                            LibraryResult.ofItem(visibleItem, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)'''
once(playlist_lookup,playlist_lookup_replacement,"playlist folder onGetItem consistent with browse rows")

# The fallback when switching Radio -> Streaming must be a playable Song
# MediaItem (raw YouTube song ID + URI), not a browse-only path MediaItem.
fallback='''        internal suspend fun firstPolarisStreamingItem(): MediaItem? =
            database.quickPicks().first().firstOrNull()
                ?.toMediaItem(MusicService.HOME_QUICK_PICKS)
                ?: database.recentSongs(16).first().firstOrNull()
                    ?.toMediaItem(MusicService.RECENT)'''
fallback_fixed='''        internal suspend fun firstPolarisStreamingItem(): MediaItem? =
            database.quickPicks().first().firstOrNull()
                ?.toMediaItem()
                ?: database.recentSongs(16).first().firstOrNull()
                    ?.toMediaItem()'''
once(fallback,fallback_fixed,"radio-to-streaming fallback playable URI")

# Support AA clients that use addMediaItems rather than setMediaItems for a
# selected radio station. Do not hand URI-less station items to ExoPlayer.
add_anchor='''                    .flatMap { item ->
                        val query =
                            item.requestMetadata.searchQuery'''
add_replacement='''                    .flatMap { item ->
                        val stationId = item.mediaId.takeIf { it.startsWith("polaris:station:") }
                            ?.removePrefix("polaris:station:")
                            ?.takeIf { RadioCatalog.find(it) != null }
                        if (stationId != null) {
                            val resolved = runCatching {
                                PolarisRadioPlayback.resolveUrl(stationId)
                            }.onFailure {
                                app.hush.music.polaris.PolarisRadioDiagnostics.record(
                                    context, "AA station add failed=" + it.javaClass.simpleName
                                )
                            }.getOrNull()
                            if (resolved != null) {
                                listOf(PolarisRadioPlayback.mediaItem(stationId, resolved))
                            } else emptyList()
                        } else {
                        val query =
                            item.requestMetadata.searchQuery'''
once(add_anchor,add_replacement,"radio onAddMediaItems resolves source URL")
add_close='''                            resolved.ifEmpty { listOf(item) }
                        }
                    }.toMutableList()'''
add_close_new='''                            resolved.ifEmpty { listOf(item) }
                        }
                        }
                    }.toMutableList()'''
once(add_close,add_close_new,"radio add branch braces")
callback.write_text(s)

# Make a radio/streaming mode switch use the same validated radio queue as
# direct AA station selection, instead of the obsolete one-item radio path.
service_s=service.read_text()
radio_old='''                    val url = withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(radioId)
                    }
                    if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(request)) {
                        return@launch
                    }
                    app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                    player.setMediaItem(
                        app.hush.music.polaris.PolarisRadioPlayback.mediaItem(radioId, url)
                    )
                    player.prepare()'''
radio_new='''                    val selectedQueue = withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioQueueBuilder.build(this@MusicService, radioId)
                    }
                    if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(request)) {
                        return@launch
                    }
                    app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                    player.setMediaItems(selectedQueue.items, selectedQueue.index, 0L)
                    player.prepare()'''
assert service_s.count(radio_old)==1,"mode switch playback anchor mismatch"
service.write_text(service_s.replace(radio_old,radio_new,1))
print("Build57: AA Radio getItem + playlist browse-only folders + playable streaming fallback + addItem + validated radio switch")
