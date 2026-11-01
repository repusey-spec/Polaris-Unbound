#!/usr/bin/env python3
"""Build #50 Hush Library 01 (Hush 13.14.7) — audited MINIMAL UI patch.

Apply AFTER all original Build #50 overlays; leave upstream library,
database, playlistChildren(), onSetMediaItems(), radio and voice untouched.

Only changes:
  1. Streaming first screen renders original Hush PLAYLIST children.
  2. Radio browse no longer injects a Streaming shortcut as a playlist item.
  3. The Radio/Streaming ROOT shortcuts have no music-note artwork.
  4. Remove reciprocal-mode custom action/button and its unused switch helper.
  5. New package version, preserving Build #50 package ID + signing key.
"""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1]).resolve()
music = root / "app/src/main/kotlin/app/hush/music"
callback = music / "playback/MediaLibrarySessionCallback.kt"
service = music / "playback/MusicService.kt"
gradle = root / "app/build.gradle.kts"

def once(s: str, a: str, b: str, label: str):
    n = s.count(a)
    assert n == 1, f"{label}: expected exactly one anchor, got {n}"
    return s.replace(a, b, 1)

c = callback.read_text(encoding="utf-8")
start = c.index("        override fun onGetChildren(")
end = c.index("        override fun onGetItem(", start)
browse = c[start:end]

root_start = browse.index('                        MusicService.ROOT -> {')
radio_start = browse.index('                        "polaris:radio" -> {', root_start)
stream_start = browse.index('                        "polaris:streaming" -> {', radio_start)
home_start = browse.index('                        MusicService.HOME -> {', stream_start)
playlist_start = browse.index('                        MusicService.PLAYLIST -> {', home_start)
playlist_end = browse.index('\n                        else -> {\n', playlist_start)

# Hush's ORIGINAL local playlist implementation is the only source of
# favourites, downloads and personal playlists. No new network or database logic.
playlist_block = browse[playlist_start:playlist_end]
assert playlist_block.rstrip().endswith('                        }'), "Hush PLAYLIST boundary moved"
playlist_body = playlist_block[len('                        MusicService.PLAYLIST -> {\n'):]
playlist_body, after = playlist_body.rsplit('\n                        }', 1)
assert after.strip() == ""
for anchor in [
    'database.likedSongsCount().first()',
    'PlaylistEntity.LIKED_PLAYLIST_ID',
    'PlaylistEntity.DOWNLOADED_PLAYLIST_ID',
    'database.playlists(PlaylistSortType.CUSTOM, descending = false).first()',
    'playlist.playlist.name',
    'playlist.songCount',
    'playlist.thumbnails.firstOrNull()?.toUri()',
]:
    assert anchor in playlist_body, f"Hush original playlist anchor missing: {anchor}"

# Original Hush queueMediaItem flags, IDs, titles, counts and artwork are
# retained exactly. Android Auto metadata hints change only row presentation.
stream = '''                        "polaris:streaming" -> {
                            val hushItems = run {
''' + playlist_body + '''
                            }
                            hushItems.map { item ->
                                val extras = android.os.Bundle(
                                    item.mediaMetadata.extras ?: android.os.Bundle()
                                )
                                extras.putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                                extras.putInt(
                                    "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",
                                    CONTENT_STYLE_LIST_ITEM,
                                )
                                extras.putInt(
                                    "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",
                                    CONTENT_STYLE_LIST_ITEM,
                                )
                                item.buildUpon().setMediaMetadata(
                                    item.mediaMetadata.buildUpon().setExtras(extras).build()
                                ).build()
                            }
                        }

'''
assert 'YouTube.library(' not in stream, "No new direct YouTube library route"
assert 'return@future' not in stream, "Keep outer onGetChildren paging path"

# The Build49 reciprocal cross-link was incorrectly shown as a giant
# Streaming note/logo row in the RADIO station list. Remove this one child,
# keeping the original RadioCatalog station list untouched.
radio = browse[radio_start:stream_start]
old_cross = '''                            listOf(
                                browsableMediaItem(
                                    "polaris:streaming",
                                    "Streaming",
                                    "음악 추천과 보관함",
                                    drawableUri(R.drawable.music_note),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                )
                            ) + PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find).map { station ->'''
new_cross = '''                            PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find).map { station ->'''
radio = once(radio, old_cross, new_cross, "remove giant Streaming row from Radio")

# The top-level Radio/Streaming ROOT folder shortcuts were created with music
# note icons in patch_hush_browse.py; do not advertise any note artwork.
root_browse = browse[root_start:radio_start]
assert root_browse.count("drawableUri(R.drawable.music_note)") == 2, "Expected exactly two top-tab notes"
root_browse = root_browse.replace("drawableUri(R.drawable.music_note)", "null")
assert '"polaris:radio"' in root_browse and '"polaris:streaming"' in root_browse

browse = (browse[:root_start] + root_browse + radio + stream
          + browse[home_start:])
assert browse.count('                        MusicService.PLAYLIST -> {') == 1, "Hush PLAYLIST source lost"
assert '음악 추천과 보관함' not in browse, "Giant Streaming item remains"

c = c[:start] + browse + c[end:]
# Remove external reciprocal-mode command from MediaSession and controller
# permissions. Normal Radio and Streaming folders remain reachable from ROOT.
c = once(c, '        var onPolarisModeSwitch: () -> Unit = {}\n', '',
         "disable source inversion callback")
c = once(c, '                    .add(app.hush.music.polaris.PolarisAAModeCommand.command)\n', '',
         "remove inversion command permission")
c = once(c, '''                app.hush.music.polaris.PolarisAAModeCommand.ACTION -> {
                    onPolarisModeSwitch()
                }
''', '', "remove reciprocal command dispatch")
assert "onPolarisModeSwitch" not in c
callback.write_text(c, encoding="utf-8")

s = service.read_text(encoding="utf-8")
old_custom = '''            val customLayout =
                if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true) {
                    // Only the mode switch remains; no Like/Repeat/Shuffle/Add in Radio.
                    listOf(
                        CommandButton.Builder()
                            .setDisplayName("Streaming")
                            .setIconResId(R.drawable.music_note)
                            .setSessionCommand(app.hush.music.polaris.PolarisAAModeCommand.command)
                            .build()
                    )
                } else listOf(
                    CommandButton.Builder()
                        .setDisplayName("Radio")
                        .setIconResId(R.drawable.radio)
                        .setSessionCommand(app.hush.music.polaris.PolarisAAModeCommand.command)
                        .build(),'''
new_custom = '''            val customLayout =
                if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true) {
                    // Live radio has no music-only custom actions.
                    emptyList()
                } else listOf('''
s = once(s, old_custom, new_custom, "remove reciprocal mode buttons, preserve Hush music actions")
s = once(s, '            onPolarisModeSwitch = ::switchPolarisMode\n', '',
         "stop registering removed source-switch callback")

# Remove Build49 helper, not the Radio player, radio history, voice recognizer
# or projection-recovery logic. This helper is no longer registered anywhere.
old_helpers = '''    private var polarisLastStreamingItem: MediaItem? = null
    private var polarisLastRadioId: String? = null

    private fun switchPolarisMode() {'''
helper_start = s.index(old_helpers)
helper_end = s.index('    private fun createMediaSourceFactory() =', helper_start)
s = s[:helper_start] + s[helper_end:]
old_history = '''        val changedId = mediaItem?.mediaId
        if (changedId?.startsWith("polaris:station:") == true) {
            polarisLastRadioId = changedId.removePrefix("polaris:station:")
        } else if (mediaItem != null && mediaItem.mediaMetadata.isPlayable == true) {
            polarisLastStreamingItem = mediaItem
        }
'''
s = once(s, old_history, '', "remove unused mode-switch state only")
assert "PolarisAAModeCommand" not in s
assert "switchPolarisMode" not in s
assert "PolarisRadioNetwork.radioActive" in s, "Radio player state unexpectedly removed"
service.write_text(s, encoding="utf-8")

# Same installed app and signing as Build50, but a sequential new versionCode
# above the discarded Build5 6.1, so Android can update without clearing data.
g = gradle.read_text(encoding="utf-8")
g = once(g,
    'versionCode = 100000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)',
    'versionCode = 100563',
    "pin unique Build50 library test code")
g = once(g, 'versionName = "13.14.7"', 'versionName = "50.1"',
         "mark explicit Build50 HushLibrary test")
assert 'applicationIdSuffix = ".polarisradio.debug"' in g, "Must preserve Build50 app ID"
gradle.write_text(g, encoding="utf-8")

print("PASS: Hush 13.14.7 original PLAYLIST root copied verbatim into Streaming")
print("PASS: no extra Radio/Streaming browse cross-links or large note item")
print("PASS: top Radio/Streaming notes removed; folders preserved")
print("PASS: reciprocal mode switch removed from UI, MediaSession and dead helper")
print("PASS: Radio playback, voice, original Hush playlist routes untouched")
print("PASS: Build50 identity preserved, new version=50.1 code=100563")
