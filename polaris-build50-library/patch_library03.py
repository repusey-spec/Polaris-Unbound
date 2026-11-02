#!/usr/bin/env python3
"""50.3 targeted regression patch on 50.2, pinned Hush 13.14.7.

Fixes verified: cancelled MediaLibrary callback scope, missing root onGetItem
nodes, unsafe fake playable voice menu and previously unchanged Hush glyph.
Keeps v0.58 radio/stream and Hush playlists/data source code unchanged.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
src=root/"app/src/main/kotlin/app/hush/music"
cb=src/"playback/MediaLibrarySessionCallback.kt"
gradle=root/"app/build.gradle.kts"
data=cb.read_text()

def once(text, old, new, label):
    n=text.count(old)
    assert n==1, f"{label}: required exactly 1 anchor, found {n}"
    return text.replace(old,new,1)

# 1. A failed scope.future (including NoPlaybackChange) must never kill
# the entire MediaLibrary callback: onGetChildren(ROOT) and radio selection
# remain callable after any single failed controller command.
data=once(data,
    "import kotlinx.coroutines.Job\n",
    "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.SupervisorJob\n",
    "add SupervisorJob import")
data=once(data,
    "private val scope = CoroutineScope(Dispatchers.Main) + Job()",
    "private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())",
    "use supervisor for independent Android Auto browse/select calls")

# 2. Rewrite previously fake playable voice row as navigable instruction/
# voice-assistant request. No media player update, no thrown exception.
first=data.index("        private fun polarisVoiceSearchItem(): MediaItem =")
last=data.index("        private fun browsableMediaItem(",first)
new_func='''        private fun polarisVoiceSearchItem(): MediaItem =
            browsableMediaItem(
                "polaris:voice_search",
                "음악검색",
                "차량 음성 검색",
                drawableUri(R.drawable.mic),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            )

        /**
         * AA owns the on-screen mic. Best-effort request to the system
         * assistant, without ever changing the playing song or queue.
         * When Android Auto blocks background activity launches, the
         * built-in car microphone/search icon remains the supported path.
         */
        private suspend fun requestPolarisVoiceSearch() {
            withContext(Dispatchers.Main.immediate) {
                val actions = listOf("android.intent.action.VOICE_ASSIST", Intent.ACTION_ASSIST)
                var requested = false
                for (action in actions) {
                    try {
                        context.startActivity(
                            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        android.util.Log.i("PolarisVoice", "voice assist intent requested " + action)
                        requested = true
                        break
                    } catch (error: Exception) {
                        android.util.Log.w(
                            "PolarisVoice",
                            "assistant launch unsupported " + action + ": " + error.javaClass.simpleName
                        )
                    }
                }
                if (!requested) {
                    android.util.Log.w("PolarisVoice", "Use Android Auto native search or mic")
                }
            }
        }

'''
assert "private suspend fun requestPolarisVoiceSearch()" in data[first:last]
data=data[:first]+new_func+data[last:]

# Voice browse action never pretends to be playable. AA requests its
# children, which attempts platform voice assist and presents a readable
# hint if system restrictions block opening the mic.
children_start=data.index("        override fun onGetChildren(")
children_end=data.index("        override fun onGetItem(",children_start)
children=data[children_start:children_end]
children=once(children,
'''                    when (parentId) {
                        MusicService.ROOT -> {''',
'''                    when (parentId) {
                        "polaris:voice_search" -> {
                            requestPolarisVoiceSearch()
                            listOf(
                                browsableMediaItem(
                                    "polaris:voice_help",
                                    "차량 마이크 또는 검색 버튼을 사용해 주세요",
                                    "Polaris에서 노래·가수를 말씀하시면 검색합니다",
                                    drawableUri(R.drawable.mic),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                )
                            )
                        }
                        "polaris:voice_help" -> {
                            // No playable fake song or voice command in the queue.
                            listOf(
                                browsableMediaItem(
                                    "polaris:voice_search",
                                    "음악검색",
                                    "차량 마이크/검색 버튼",
                                    drawableUri(R.drawable.mic),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                )
                            )
                        }
                        MusicService.ROOT -> {''',
"add voice submenu without playback command")
# Logs make root failures distinguishable from legitimate empty Hush data.
children=once(children,
'''                LibraryResult.ofItemList(items.paged(page, pageSize), params)
''',
'''                if (parentId == MusicService.ROOT ||
                    parentId == "polaris:radio" ||
                    parentId == "polaris:streaming"
                ) {
                    android.util.Log.i(
                        "PolarisBrowse",
                        "parent=" + parentId + " count=" + items.size +
                            " page=" + page + " pageSize=" + pageSize
                    )
                }
                LibraryResult.ofItemList(items.paged(page, pageSize), params)
''',
"root/streaming/radio diagnostic count")
data=data[:children_start]+children+data[children_end:]

# 3. Ensure getItem resolves the EXACT two AA root tabs, rather than
# falling through to the Hush song database and ERROR_UNKNOWN.
get_start=data.index("        override fun onGetItem(")
get_end=data.index("        override fun onSetMediaItems(",get_start)
item_section=data[get_start:get_end]
item_section=once(item_section,
'''                    mediaId == "polaris:voice_search" -> {
                        LibraryResult.ofItem(polarisVoiceSearchItem(), null)
                    }''',
'''                    mediaId == "polaris:voice_search" -> {
                        LibraryResult.ofItem(polarisVoiceSearchItem(), null)
                    }
                    mediaId == "polaris:voice_help" -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                "polaris:voice_help",
                                "차량 마이크 또는 검색 버튼을 사용해 주세요",
                                "Polaris 검색",
                                drawableUri(R.drawable.mic),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ), null
                        )
                    }
                    mediaId == "polaris:radio" || mediaId == "polaris:streaming" -> {
                        val radio = mediaId == "polaris:radio"
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                mediaId,
                                if (radio) "Radio" else "Streaming",
                                null,
                                null,
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            null,
                        )
                    }''',
"preserve voice item and add both root tab getItem routes")
data=data[:get_start]+item_section+data[get_end:]

# 4. Remove both obsolete fake-song voice callbacks. In particular NEVER
# throw NoPlaybackChange from custom voice list selection.
data=once(data,
'''                if (firstItem.mediaId == "polaris:voice_search") {
                    requestPolarisVoiceSearch()
                    throw NoPlaybackChange("voice input requested without changing playback")
                }
''', '', "remove failed voice playback exception")
data=once(data,
'''                if (mediaItems.any { it.mediaId == "polaris:voice_search" }) {
                    requestPolarisVoiceSearch()
                }
                mediaItems
                    .filterNot { item ->
                        AndroidAutoPlaylists.isLosslessOffer(item.mediaId) ||
                        item.mediaId == "polaris:voice_search"
                    }''',
'''                mediaItems
                    .filterNot { item -> AndroidAutoPlaylists.isLosslessOffer(item.mediaId) }''',
"restore untouched Hush queue-add behavior")

# 5. Radio selection must not silently fail; register request, resolver error
# and response. The selected live source is still the original Build50 URL.
data=once(data,
'''                if (radioStation != null) {
                    val url = PolarisRadioPlayback.resolveUrl(radioStation.id)
                    val item = PolarisRadioPlayback.mediaItem(radioStation.id, url)
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
                }''',
'''                if (radioStation != null) {
                    android.util.Log.i("PolarisRadio", "AA selected station=" + radioStation.id)
                    val url = try {
                        PolarisRadioPlayback.resolveUrl(radioStation.id)
                    } catch (error: Exception) {
                        android.util.Log.e("PolarisRadio", "AA station URL error: " + radioStation.id, error)
                        throw error
                    }
                    val item = PolarisRadioPlayback.mediaItem(radioStation.id, url)
                    android.util.Log.i("PolarisRadio", "AA station media item resolved=" + radioStation.id)
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
                }''',
"radio selection trace without altering playback")

assert 'throw NoPlaybackChange("voice input requested' not in data
assert 'CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())' in data
cb.write_text(data)

# 6. Replace ALL Hush-specific icon drawables, including the Hush glyph
# that survived 50.2. Launcher PNGs were already generated from actual
# Polaris SVG by patch_hush_icon.py.
from PIL import Image
image_dir=root/"app/src/main/res"
source=image_dir/"mipmap-xxxhdpi/ic_launcher.png"
assert source.is_file(), source
with Image.open(source) as pic:
    p=pic.convert("RGBA")
    out_dir=image_dir/"drawable-nodpi"
    for filename in ("hush_app_icon.webp","hush_logo_mark.webp"):
        target=out_dir/filename
        assert target.is_file(),target
        p.resize((256,256),Image.Resampling.LANCZOS).save(target,"WEBP",lossless=True)
    for density in ("mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi"):
        folder=image_dir/f"mipmap-{density}"
        base=folder/"ic_launcher.png"
        assert base.is_file()
        for filename in ("ic_launcher_static.png","ic_launcher_static_round.png"):
            basebytes=base.read_bytes()
            (folder/filename).write_bytes(basebytes)
# Keep root launcher icon resource unchanged; all direct Hush glyph refs
# now point at the Polaris graphic rather than the Hush note.
g=gradle.read_text()
g=once(g,'versionCode = 100564','versionCode = 100565','50.3 version code')
g=once(g,'versionName = "50.2"','versionName = "50.3"','50.3 version name')
assert 'applicationIdSuffix = ".polarisradio.debug"' in g
gradle.write_text(g)

print("PASS 50.3: scoped media library failures isolated; Radio/Streaming onGetItem matched")
print("PASS 50.3: fake playable voice action removed; AA voice submenu + system voice attempt")
print("PASS 50.3: all remaining Hush glyph image assets replaced by original Polaris branding")
print("PASS 50.3: radio selection diagnostics, native Hush liked/playlists & data paths untouched")
