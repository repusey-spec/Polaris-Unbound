#!/usr/bin/env python3
"""Source-level check: Streaming tab must reuse the original Hush library."""
from pathlib import Path
import sys
source=(Path(sys.argv[1]) / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt").read_text()
checks = {
    "AA Streaming tab routes to existing playlist root":
      'when (if (parentId == "polaris:streaming") MusicService.PLAYLIST else parentId)' in source,
    "Streaming tab has browsable getItem metadata":
      'mediaId == "polaris:streaming" -> {' in source,
    "same liked-songs count as working Hush library":
      'database.likedSongsCount().first()' in source,
    "same downloaded-songs count as working Hush library":
      'val downloadedSongCount = downloadUtil.downloads.value.size' in source,
    "same existing user playlist order":
      'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()' in source,
    "playlist item identities unchanged":
      'playlist.id' in source and 'MusicService.PLAYLIST' in source,
    "existing playlist browse and playback preserved":
      'playlistChildren(' in source and 'polarisPlaylistMediaItems(' in source,
    "radio browse remains independently available":
      '"polaris:radio" -> {' in source,
}
for key, passed in checks.items():
    print(("PASS" if passed else "FAIL")+": "+key)
assert all(checks.values()), "Streaming playlist-home validation failed"
