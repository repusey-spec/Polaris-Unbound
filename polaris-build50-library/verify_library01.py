#!/usr/bin/env python3
"""Fail-fast structural verification for Build50 HushLibrary01.

Checks the final overlaid Hush checkout; compilation and on-car execution
are additional, separate gates.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
music = root / "app/src/main/kotlin/app/hush/music"
cb = (music / "playback/MediaLibrarySessionCallback.kt").read_text()
svc = (music / "playback/MusicService.kt").read_text()
gradle = (root / "app/build.gradle.kts").read_text()

browse = cb[cb.index("        override fun onGetChildren("):
            cb.index("        override fun onGetItem(")]
root_beg = browse.index('                        MusicService.ROOT -> {')
radio_beg = browse.index('                        "polaris:radio" -> {', root_beg)
stream_beg = browse.index('                        "polaris:streaming" -> {', radio_beg)
home_beg = browse.index('                        MusicService.HOME -> {', stream_beg)
hush_beg = browse.index('                        MusicService.PLAYLIST -> {', home_beg)
next_beg = browse.index('\n                        else -> {\n', hush_beg)
header = browse[root_beg:radio_beg]
stations = browse[radio_beg:stream_beg]
stream = browse[stream_beg:home_beg]
hush = browse[hush_beg:next_beg]

checks = {
    "same Build50 Android package ID and new version":
        'applicationIdSuffix = ".polarisradio.debug"' in gradle and
        'versionCode = 100563' in gradle and
        'versionName = "50.1"' in gradle,
    "root keeps Radio and Streaming selectors":
        '"polaris:radio"' in header and '"polaris:streaming"' in header,
    "root selectors have no music-note icons":
        'drawableUri(R.drawable.music_note)' not in header and
        header.count('null,') >= 4,
    "radio browse is only original v0.58 RadioCatalog stations":
        'PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find)' in stations and
        '"polaris:streaming"' not in stations,
    "no giant streaming title in Radio browse":
        "음악 추천과 보관함" not in stations,
    "streaming home has exactly native Hush 2 shortcuts":
        'PlaylistEntity.LIKED_PLAYLIST_ID' in stream and
        'PlaylistEntity.DOWNLOADED_PLAYLIST_ID' in stream,
    "streaming home uses native Hush playlist database":
        'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()' in stream,
    "streaming home uses Hush artwork titles counts":
        'playlist.thumbnails.firstOrNull()?.toUri()' in stream and
        'playlist.playlist.name' in stream and 'playlist.songCount' in stream,
    "streaming home reuses Hush media item semantics":
        'queueMediaItem(' in stream and
        'hushItems.map { item ->' in stream,
    "list metadata hint without replacing playback IDs":
        'CONTENT_STYLE_LIST_ITEM' in stream and
        'item.buildUpon().setMediaMetadata(' in stream,
    "no direct YTM account fetch or hardcoded screenshot examples":
        'YouTube.library(' not in stream and
        'California Beach' not in stream and 'sweet' not in stream,
    "Hush original playlist tree preserved verbatim":
        'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()' in hush and
        'PlaylistEntity.LIKED_PLAYLIST_ID' in hush and
        'PlaylistEntity.DOWNLOADED_PLAYLIST_ID' in hush,
    "Hush original playlistChildren and player paths still exist":
        'private suspend fun playlistChildren(' in cb and
        'override fun onSetMediaItems(' in cb,
    "reciprocal switch no longer registered or dispatched":
        'onPolarisModeSwitch' not in cb and
        'PolarisAAModeCommand' not in cb and
        'PolarisAAModeCommand' not in svc and
        'switchPolarisMode' not in svc,
    "Hush streaming actions preserved outside live radio":
        'if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true)' in svc and
        'emptyList()' in svc and
        'else listOf(' in svc,
    "original live radio playback engine untouched":
        'PolarisRadioNetwork.radioActive' in svc and
        'polarisProjectionRecovery.onMediaItemTransition(mediaItem)' in svc,
    "Build49 unsafe radio queue class not packaged":
        not (music / "polaris/PolarisRadioQueue.kt").exists(),
    "removed command class not packaged":
        not (music / "polaris/PolarisAAModeCommand.kt").exists(),
    "Voice command handler remains":
        'resolveVoiceMediaItems(' in cb,
}
for title,ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + title)
assert all(checks.values()), "Build50 HushLibrary01 regression check FAILED"
print("PASS: source-only regression complete; Gradle and real device remain separate checks")
