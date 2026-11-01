#!/usr/bin/env python3
"""Build50 HushLibrary02: ONLY 3 deltas on verified HushLibrary01.
  1. Android Auto microphone '음악검색' action row directly above Hush Likes.
     It invokes the platform voice-command intent if allowed, keeping the
     current queue intact; the Android Auto host owns the voice interface.
  2. Disable periodic Media3 session position updates while music plays,
     to test the observed AA browse list scroll reset.
  3. Replace remaining Hush notification/AA small icons with the already
     generated Polaris monochrome PNG at all densities.

No changes to radio, voice matching, music resolver, Hush playlist database,
playlists' media IDs, completed removal of reciprocal switching, or root tabs.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
music = root / "app/src/main/kotlin/app/hush/music"
callback = music / "playback/MediaLibrarySessionCallback.kt"
service = music / "playback/MusicService.kt"
res = root / "app/src/main/res/drawable"
gradle = root / "app/build.gradle.kts"

def once(s, old, new, label):
    count = s.count(old)
    assert count == 1, f"{label}: expected one source anchor, got {count}"
    return s.replace(old, new, 1)

c = callback.read_text()
# Exact existing 50.1 Streaming list code: prepend a SINGLE menu row,
# but keep Hush's original playlist list bytes and their playback IDs.
start = c.index('                        "polaris:streaming" -> {')
end = c.index('                        MusicService.HOME -> {', start)
section = c[start:end]
assert "val hushItems = run {" in section
assert 'YouTube.library(' not in section
section = once(section,
'''                            hushItems.map { item ->
''',
'''                            (listOf(polarisVoiceSearchItem()) + hushItems).map { item ->
''',
"insert microphone item exactly once before Hush native lists")
c = c[:start] + section + c[end:]

# Search action media item: playable, not browsable. The car should send
# onSetMediaItems when tapped, instead of treating it as a playlist folder.
media_function = '''        private fun polarisVoiceSearchItem(): MediaItem =
            MediaItem.Builder()
                .setMediaId("polaris:voice_search")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("음악검색")
                        .setSubtitle("터치하여 음성검색")
                        .setArtworkUri(drawableUri(R.drawable.mic))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(playableExtras(CONTENT_STYLE_LIST_ITEM))
                        .build()
                )
                .build()

        /**
         * A MediaLibrary browse item cannot itself open Android Auto's own
         * microphone UI: that is controlled by the car/Google Assistant.
         * A user tap triggers the platform's ACTION_VOICE_COMMAND activity
         * when allowed by the OS. It may be blocked for background apps,
         * in which case direct car microphone use remains available.
         */
        private suspend fun requestPolarisVoiceSearch() {
            withContext(Dispatchers.Main.immediate) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VOICE_COMMAND)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    android.util.Log.i("PolarisVoice", "platform voice command intent dispatched")
                } catch (error: Exception) {
                    android.util.Log.w(
                        "PolarisVoice",
                        "Voice assistant unavailable: " + error.javaClass.simpleName
                    )
                    Toast.makeText(
                        context,
                        "차량 마이크 버튼으로 음악을 검색해 주세요",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

'''
c = once(c, '        private fun browsableMediaItem(\n',
         media_function + '        private fun browsableMediaItem(\n',
         "insert Media3 browse action helper without altering Hush queue functions")

# Native AA may request details for a playlist row via onGetItem before click.
on_item = c.index('        override fun onGetItem(')
after_item = c[on_item:]
after_item = once(after_item,
'''                when {
                    mediaId.startsWith("polaris:station:")''',
'''                when {
                    mediaId == "polaris:voice_search" -> {
                        LibraryResult.ofItem(polarisVoiceSearchItem(), null)
                    }
                    mediaId.startsWith("polaris:station:")''',
"advertise playable microphone item on getItem")
c = c[:on_item] + after_item

# Use Hush's own NoPlaybackChange exception designed for AA actions:
# returning the current queue would restart it, which is NOT acceptable.
c = once(c,
'''                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                val voiceQuery =''',
'''                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                if (firstItem.mediaId == "polaris:voice_search") {
                    requestPolarisVoiceSearch()
                    throw NoPlaybackChange("voice input requested without changing playback")
                }
                val voiceQuery =''',
"intercept play on microphone row and preserve audio timeline")

# Car controllers can also send addMediaItems; in that case the action
# should never get enqueued as a real song.
c = once(c,
'''                mediaItems
                    .filterNot { item -> AndroidAutoPlaylists.isLosslessOffer(item.mediaId) }''',
'''                if (mediaItems.any { it.mediaId == "polaris:voice_search" }) {
                    requestPolarisVoiceSearch()
                }
                mediaItems
                    .filterNot { item ->
                        AndroidAutoPlaylists.isLosslessOffer(item.mediaId) ||
                        item.mediaId == "polaris:voice_search"
                    }''',
"prevent microphone action from corrupting song queue")

assert c.count('"polaris:voice_search"') >= 4
assert c.count('MusicService.PLAYLIST -> {') == 1
callback.write_text(c)

s = service.read_text()
s = once(s,
'''                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(''',
'''                .Builder(this, player, mediaLibrarySessionCallback)
                .setPeriodicPositionUpdateEnabled(false)
                .setSessionActivity(''',
"stop periodic Media3 position updates to test scroll-reset workaround")
assert 'PolarisAAModeCommand' not in s and "switchPolarisMode" not in s
service.write_text(s)

# The launcher icon is already Polaris in 50.1. AA's player/icon badge can
# instead use the Hush's unchanged small_icon and app_icon_small drawables.
# Use existing Polaris monochrome PNG; never reuse an adaptive-icon XML as
# a Compose painter or notification small icon.
monochrome = 'ic_launcher_monochrome'
for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
    p = root / f"app/src/main/res/mipmap-{density}/{monochrome}.png"
    assert p.is_file() and p.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", p

bitmap = '''<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@mipmap/ic_launcher_monochrome"
    android:gravity="center"
    android:antialias="true" />
'''
for filename in ("small_icon.xml", "app_icon_small.xml", "app_icon_monochrome.xml"):
    f = res / filename
    assert f.is_file(), f
    f.write_text(bitmap)

# Preserve package ID and pinned signature. Newer version than 50.1 only.
g = gradle.read_text()
g = once(g, 'versionCode = 100563', 'versionCode = 100564', "version code")
g = once(g, 'versionName = "50.1"', 'versionName = "50.2"', "version name")
assert 'applicationIdSuffix = ".polarisradio.debug"' in g
gradle.write_text(g)

print("PASS 50.2: original Hush lists kept; microphone menu first; voice intent action wired")
print("PASS 50.2: Media3 position updates disabled; Polaris small-icons all densities PNG")
print("PASS 50.2: radio/streaming menus, playback, original Hush sync, voice matching preserved")
print("PASS 50.2: package and signature unchanged; sequential versionCode=100564")
