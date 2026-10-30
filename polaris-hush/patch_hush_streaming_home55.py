#!/usr/bin/env python3
"""Use the existing Hush playlist library as Polaris AA Streaming's first page.

Apply after patch_hush_aa53.py in the pinned Hush checkout.
This redirects the AA browse parent "polaris:streaming" to the working
MusicService.PLAYLIST browse case, preserving playlist media IDs and actions.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
callback = root / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
source = callback.read_text()

old = """                val items =
                    when (parentId) {"""
new = """                val items =
                    // AA Streaming opens the same Hush library as "보관함 · 플레이리스트".
                    when (if (parentId == "polaris:streaming") MusicService.PLAYLIST else parentId) {"""
assert source.count(old) == 1, "Unexpected MediaLibrarySessionCallback browse version"
source = source.replace(old, new, 1)

item_anchor = """                    mediaId == MusicService.ROOT -> {"""
item_replacement = """                    mediaId == "polaris:streaming" -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                "polaris:streaming",
                                "Streaming",
                                null,
                                drawableUri(R.drawable.queue_music),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            ),
                            null,
                        )
                    }

""" + item_anchor
assert source.count(item_anchor) == 1, "Unexpected onGetItem media-root anchor"
source = source.replace(item_anchor, item_replacement, 1)

assert 'MusicService.PLAYLIST -> {' in source
assert 'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()' in source
assert 'database.likedSongsCount().first()' in source
assert 'val downloadedSongCount = downloadUtil.downloads.value.size' in source
callback.write_text(source)
print("Polaris AA Streaming now opens the existing Hush playlist library")
