#!/usr/bin/env python3
"""Build50 HushLibrary02: verify only three new changes atop 50.1."""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
music=root/"app/src/main/kotlin/app/hush/music"
cb=(music/"playback/MediaLibrarySessionCallback.kt").read_text()
service=(music/"playback/MusicService.kt").read_text()
gradle=(root/"app/build.gradle.kts").read_text()
browse=cb[cb.index('        override fun onGetChildren('):
          cb.index('        override fun onGetItem(')]
stream=browse[browse.index('                        "polaris:streaming" -> {'):
              browse.index('                        MusicService.HOME -> {')]
radio=browse[browse.index('                        "polaris:radio" -> {'):
             browse.index('                        "polaris:streaming" -> {')]
hush=browse[browse.index('                        MusicService.PLAYLIST -> {'):
            browse.index('\n                        else -> {\n',browse.index('                        MusicService.PLAYLIST -> {'))]
checks={
    "version and identity preserve 50.1 install data":
        'versionCode = 100564' in gradle and 'versionName = "50.2"' in gradle and
        'applicationIdSuffix = ".polarisradio.debug"' in gradle,
    "microphone is FIRST streaming row, ahead of native Hush library":
        '(listOf(polarisVoiceSearchItem()) + hushItems).map' in stream,
    "microphone is named exactly 음악검색":
        '.setTitle("음악검색")' in cb and
        '.setArtworkUri(drawableUri(R.drawable.mic))' in cb,
    "microphone action playable and NOT a folder":
        '.setMediaId("polaris:voice_search")' in cb and
        '.setIsBrowsable(false)' in cb and '.setIsPlayable(true)' in cb,
    "search opens Android platform voice intent when permitted":
        'Intent(Intent.ACTION_VOICE_COMMAND)' in cb and
        'Intent.FLAG_ACTIVITY_NEW_TASK' in cb and
        'requestPolarisVoiceSearch()' in cb,
    "onGetItem explicitly resolves microphone action":
        'mediaId == "polaris:voice_search" -> {' in cb,
    "action not applied as new audio queue":
        'throw NoPlaybackChange("voice input requested without changing playback")' in cb and
        'item.mediaId == "polaris:voice_search"' in cb and
        'requestPolarisVoiceSearch()' in cb,
    "unavailable voice has safe fallback and logs, no invented assistant":
        'catch (error: Exception)' in cb and
        '차량 마이크 버튼으로 음악을 검색해 주세요' in cb,
    "position updates disabled on actual MediaLibrarySession":
        '.Builder(this, player, mediaLibrarySessionCallback)\n                .setPeriodicPositionUpdateEnabled(false)' in service,
    "Hush native 50.1 favorites and playlists intact":
        'PlaylistEntity.LIKED_PLAYLIST_ID' in stream and
        'PlaylistEntity.DOWNLOADED_PLAYLIST_ID' in stream and
        'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()' in stream and
        'playlist.thumbnails.firstOrNull()?.toUri()' in stream and
        'hushItems = run' in stream and
        'PlaylistEntity.LIKED_PLAYLIST_ID' in hush,
    "no new YouTube library network parser":
        'YouTube.library(' not in stream and 'California Beach' not in stream,
    "Radio and Streaming tabs are preserved":
        '                        "polaris:radio" -> {' in browse and
        '                        "polaris:streaming" -> {' in browse,
    "giant crosslink and reciprocal commands remain absent":
        '"polaris:streaming"' not in radio and
        'onPolarisModeSwitch' not in cb and 'PolarisAAModeCommand' not in service and
        'switchPolarisMode' not in service,
    "original voice matching routes still present":
        'resolveVoiceMediaItems(' in cb and
        'PolarisVoiceRouter' in cb,
    "radio engine callbacks remain intact":
        'PolarisRadioNetwork.radioActive' in service and
        'polarisProjectionRecovery.onMediaItemTransition(mediaItem)' in service,
    "source small icon now Polaris PNG, not Hush glyph":
        all((root / f"app/src/main/res/drawable/{p}").read_text().count('@mipmap/ic_launcher_monochrome')==1
            for p in ("small_icon.xml","app_icon_small.xml","app_icon_monochrome.xml")),
    "all PNG densities available":
        all((root / f"app/src/main/res/mipmap-{d}/ic_launcher_monochrome.png")
            .read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
            for d in ("mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi")),
    "launcher remains Polaris":
        'app_icon_foreground.xml' in {p.name for p in (root/"app/src/main/res/drawable").glob("*")}
}
for title,ok in checks.items():
    print(("PASS" if ok else "FAIL")+": "+title)
assert all(checks.values()),"Build50 HushLibrary02 failed source regression"
print("PASS: source checks complete; compile and actual car voice launch are separate")
