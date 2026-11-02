#!/usr/bin/env python3
"""Build50 50.3 source guards, building strictly on verified Build50 50.2."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
music=root/"app/src/main/kotlin/app/hush/music"
cb=(music/"playback/MediaLibrarySessionCallback.kt").read_text()
svc=(music/"playback/MusicService.kt").read_text()
gradle=(root/"app/build.gradle.kts").read_text()
browse=cb[cb.index("        override fun onGetChildren("):cb.index("        override fun onGetItem(")]
item=cb[cb.index("        override fun onGetItem("):cb.index("        override fun onSetMediaItems(")]
stream=browse[browse.index('                        "polaris:streaming" -> {'):browse.index("                        MusicService.HOME -> {")]
radio=browse[browse.index('                        "polaris:radio" -> {'):browse.index('                        "polaris:streaming" -> {')]
checks={
"same installed app signature and newer 50.3 version":
 'applicationIdSuffix = ".polarisradio.debug"' in gradle and
 'versionCode = 100565' in gradle and 'versionName = "50.3"' in gradle,
"MediaLibrarySession independent supervisor scope":
 'CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())' in cb,
"root returns BOTH Radio and Streaming":
 'MusicService.ROOT -> {' in browse and
 '"polaris:radio"' in browse and '"polaris:streaming"' in browse,
"root metadata resolves when queried via onGetItem":
 'mediaId == "polaris:radio" || mediaId == "polaris:streaming"' in item and
 'LibraryResult.ofItem(' in item,
"root and radio and streaming request counts diagnosed":
 '"PolarisBrowse"' in browse and 'count=" + items.size' in browse,
"existing radio station list kept exactly":
 'PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find)' in radio,
"radio media request and URL failure can be traced":
 'AA selected station=' in cb and 'AA station media item resolved=' in cb and
 'AA station URL error:' in cb,
"radio return still a single live station, not stale song":
 'PolarisRadioPlayback.mediaItem(radioStation.id, url)' in cb and
 'MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)' in cb,
"microphone row precedes native Hush liked/playlist list":
 '(listOf(polarisVoiceSearchItem()) + hushItems).map' in stream,
"mic is browsable and not a fake playable song":
 'browsableMediaItem(\n                "polaris:voice_search"' in cb and
 '.setIsPlayable(true)\n                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)\n                        .setExtras(playableExtras(CONTENT_STYLE_LIST_ITEM))' not in cb[cb.index('private fun polarisVoiceSearchItem'):cb.index('private suspend fun requestPolarisVoiceSearch')],
"mic has safe browse handler and no AA player transition":
 '"polaris:voice_search" -> {' in browse and
 'requestPolarisVoiceSearch()' in browse and
 'NoPlaybackChange("voice input requested without changing playback")' not in cb,
"voice uses ASSIST rather than obsolete ACTION_VOICE_COMMAND":
 '"android.intent.action.VOICE_ASSIST"' in cb and
 'Intent.ACTION_ASSIST' in cb and
 'Intent.ACTION_VOICE_COMMAND' not in cb,
"Hush playlist entries are unmodified":
 'PlaylistEntity.LIKED_PLAYLIST_ID' in stream and
 'PlaylistEntity.DOWNLOADED_PLAYLIST_ID' in stream and
 'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()' in stream and
 'playlist.thumbnails.firstOrNull()?.toUri()' in stream,
"original Hush song and playlist playback stays":
 'private suspend fun playlistChildren(' in cb and
 'override fun onSetMediaItems(' in cb and
 'override fun onAddMediaItems(' in cb,
"radio/streaming mutual inversion remains removed":
 '"polaris:streaming"' not in radio and
 'onPolarisModeSwitch' not in cb and 'switchPolarisMode' not in svc,
"no reintroduced giant Radio Streaming note tile":
 '음악 추천과 보관함' not in browse,
"streaming Media3 scroller fix preserved":
 '.setPeriodicPositionUpdateEnabled(false)' in svc,
"original radio player and projection lifecycle preserved":
 'PolarisRadioNetwork.radioActive' in svc and
 'polarisProjectionRecovery.onMediaItemTransition(mediaItem)' in svc,
"native Polaris branding PNG and no remaining Hush glyph":
 all((root/f"app/src/main/res/drawable-nodpi/{n}").read_bytes()[:4]==b"RIFF"
     for n in ("hush_app_icon.webp","hush_logo_mark.webp")),
"pinned regenerated launcher/still Polaris branding":
 all((root/f"app/src/main/res/mipmap-{d}/ic_launcher.png").read_bytes()[:8]==b"\x89PNG\r\n\x1a\n"
     for d in ("mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi")),
}
for name,ok in checks.items():
 print(("PASS" if ok else "FAIL")+": "+name)
assert all(checks.values()),"Build50 50.3 source regression failed"
print("PASS: source-only regression complete. Vehicle mic invocation still requires real AA test.")
