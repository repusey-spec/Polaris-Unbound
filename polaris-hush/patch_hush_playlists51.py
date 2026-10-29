#!/usr/bin/env python3
"""Build 51: play online playlist items without relying on a fully synced Room cache.

Applies after Build 50 on the pinned Hush MediaLibrarySessionCallback.
Do not touch original Hush source nor stable Polaris v0.58.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])
callback = root / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
s = callback.read_text()

def once(old, new, why):
    global s
    n = s.count(old)
    assert n == 1, f"{why}: expected one anchor, got {n}"
    s = s.replace(old, new, 1)

helper = """
        // Separate persistent local library songs from the online YouTube playlist.
        // A newly added cloud playlist may have a DB header but zero synced songs.
        private val polarisRemotePlaylistCache = ConcurrentHashMap<String, List<MediaItem>>()

        private suspend fun polarisOnlinePlaylistItems(playlistId: String): List<MediaItem> {
            val remote = runCatching {
                onlinePlaylistSongs(playlistId).map { it.toMediaItem() }
            }.getOrElse {
                app.hush.music.polaris.PolarisRadioDiagnostics.record(
                    context, "playlist remote lookup failed: " + it.javaClass.simpleName
                )
                emptyList()
            }
            if (remote.isNotEmpty()) {
                polarisRemotePlaylistCache[playlistId] = remote
                return remote
            }
            return polarisRemotePlaylistCache[playlistId].orEmpty()
        }

        private suspend fun polarisPlaylistMediaItems(
            playlistId: String,
            sortOption: AutoPlaylistSortOption? = null,
        ): List<MediaItem> {
            val local = playlistSongs(playlistId, sortOption).map { it.toMediaItem() }
            // "Liked" and "Downloaded" are intentionally offline-first.
            if (playlistId == PlaylistEntity.LIKED_PLAYLIST_ID ||
                playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID) return local

            val browseId = database.getPlaylistById(playlistId)?.playlist?.browseId
                ?.takeIf { it.isNotBlank() } ?: return local
            val online = polarisOnlinePlaylistItems(browseId)
            if (online.isEmpty()) return local
            val localById = local.associateBy { it.mediaId }
            val onlineIds = online.mapTo(HashSet<String>()) { it.mediaId }
            val merged = online.map { localById[it.mediaId] ?: it } +
                local.filterNot { it.mediaId in onlineIds }
            // Preserve the user's explicit local sort choice when present.
            return if (sortOption == null) merged else if (local.isNotEmpty()) local else merged
        }

        private suspend fun polarisPlaylistBrowseItems(
            playlistId: String,
            parentId: String,
            sortOption: AutoPlaylistSortOption? = null,
        ): List<MediaItem> =
            polarisPlaylistMediaItems(playlistId, sortOption).map { item ->
                item.buildUpon().setMediaId(parentId + "/" + item.mediaId).build()
            }

        private suspend fun polarisPlaylistSongItem(
            playlistId: String,
            songId: String,
            parentId: String,
        ): MediaItem? =
            database.song(songId).first()?.toMediaItem(parentId)
                ?: polarisPlaylistMediaItems(playlistId).firstOrNull { it.mediaId == songId }
                    ?.buildUpon()?.setMediaId(parentId + "/" + songId)?.build()

"""
once(
    '        private suspend fun playlistChildren(\n',
    helper + '        private suspend fun playlistChildren(\n',
    "insert playlist fallback helpers",
)
once(
    '                    actionItems + playlistSongs(playlistId).map { it.toMediaItem(parentId) }',
    '                    actionItems + polarisPlaylistBrowseItems(playlistId, parentId)',
    "browse unsynced playlist music",
)
once(
    '                        playlistSongs(playlistId, sortOption).map { it.toMediaItem(parentId) }',
    '                        polarisPlaylistBrowseItems(playlistId, parentId, sortOption)',
    "browse sorted unsynced music",
)
once(
    '                    playlistSongs(playlistId).shuffled().map { it.toMediaItem(parentId) }',
    '                    polarisPlaylistBrowseItems(playlistId, parentId).shuffled()',
    "browse shuffled unsynced music",
)
# Resolve selected remote tracks in browsed playlists, not just DB rows.
once(
    '                            database.song(songId).first()?.toMediaItem(mediaId.substringBeforeLast(\'/\'))',
    '                            polarisPlaylistSongItem(playlistId, songId, mediaId.substringBeforeLast(\'/\'))',
    "selected shuffled song fallback",
)
once(
    '                            database.song(songId).first()?.toMediaItem(mediaId.substringBeforeLast(\'/\'))',
    '                            polarisPlaylistSongItem(playlistId, songId, mediaId.substringBeforeLast(\'/\'))',
    "selected sorted song fallback",
)
once(
    '                    database.song(songId).first()?.toMediaItem(mediaId.substringBeforeLast(\'/\'))',
    '                    polarisPlaylistSongItem(playlistId, songId, mediaId.substringBeforeLast(\'/\'))',
    "selected ordinary song fallback",
)
old_play = """                        val songs =
                            playlistSongs(
                                playlistId = playlistId,
                                sortOption = sortOption,
                            ).let { songs ->
                                if (action == PLAYLIST_ACTION_SHUFFLE) songs.shuffled() else songs
                            }
                        if (action == PLAYLIST_ACTION_SHUFFLE) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        MediaSession.MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            selectedSongId?.let { songId ->
                                songs.indexOfFirst { it.id == songId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )"""
new_play = """                        val playable = polarisPlaylistMediaItems(
                            playlistId = playlistId,
                            sortOption = sortOption,
                        ).let { items ->
                            if (action == PLAYLIST_ACTION_SHUFFLE) items.shuffled() else items
                        }
                        if (playable.isEmpty()) {
                            app.hush.music.polaris.PolarisRadioDiagnostics.record(
                                context, "playlist resolved 0 songs; kept previous playback"
                            )
                            return@future mediaSession.currentMediaItemsWithStartPosition()
                        }
                        if (action == PLAYLIST_ACTION_SHUFFLE) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        MediaSession.MediaItemsWithStartPosition(
                            playable,
                            selectedSongId?.let { songId ->
                                playable.indexOfFirst { it.mediaId == songId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )"""
once(old_play, new_play, "AA local and online playlist queue resolution")
old_online = """                        val songs =
                            onlinePlaylistSongs(playlistId).let { songs ->
                                if (action == PLAYLIST_ACTION_SHUFFLE) songs.shuffled() else songs
                            }
                        if (action == PLAYLIST_ACTION_SHUFFLE) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        val mediaItems = songs.map { it.toMediaItem() }
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            selectedSongId?.let { songId ->
                                mediaItems.indexOfFirst { it.mediaId == songId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )"""
new_online = """                        val mediaItems = polarisOnlinePlaylistItems(playlistId).let { items ->
                            if (action == PLAYLIST_ACTION_SHUFFLE) items.shuffled() else items
                        }
                        if (mediaItems.isEmpty()) {
                            app.hush.music.polaris.PolarisRadioDiagnostics.record(
                                context, "online playlist returned 0 songs; kept previous playback"
                            )
                            return@future mediaSession.currentMediaItemsWithStartPosition()
                        }
                        if (action == PLAYLIST_ACTION_SHUFFLE) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            selectedSongId?.let { songId ->
                                mediaItems.indexOfFirst { it.mediaId == songId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )"""
once(old_online,new_online,"remote YouTube playlist queue fallback")
once(
    '                    ) + onlinePlaylistSongs(playlistId).map { it.toMediaItem(parentId) }',
    '                    ) + polarisOnlinePlaylistItems(playlistId).map { it.buildUpon().setMediaId(parentId + "/" + it.mediaId).build() }',
    "browse remote playlist with cache",
)
once(
    '                    onlinePlaylistSongs(playlistId).map { it.toMediaItem(parentId) }',
    '                    polarisOnlinePlaylistItems(playlistId).map { it.buildUpon().setMediaId(parentId + "/" + it.mediaId).build() }',
    "browse shuffled remote playlist",
)
callback.write_text(s)
print("Build 51: AA playlists prefer online metadata for unsynced songs, cache online lists, and do not erase playback on empty results")
