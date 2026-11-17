#!/usr/bin/env python3
"""6.10: remove forced 2-column streaming category grid and enlarge radio artwork.

Leaves InterTune engine, playlists, source restore, station URLs, account, storage and
radio queue intact. Android Auto owns final layout; avoid requesting CATEGORY_GRID_ITEM
for a nested Streaming browser. Only the live programme is burned into browse art.
"""
from pathlib import Path
import re
import sys

root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"

def replace_exact(path, old, new, label, count=1):
    s=path.read_text(encoding="utf-8")
    found=s.count(old)
    if found != count:
        raise AssertionError(f"6.10 {label}: {found} occurrences, expected {count}")
    path.write_text(s.replace(old,new,count),encoding="utf-8")

catalog=pkg/"polaris/RadioCatalog.kt"
s=catalog.read_text(encoding="utf-8")
prior='''    const val RADIO_BROWSE_ROOT_ID = "polaris:radio:v609"
    const val STREAMING_BROWSE_ROOT_ID = "polaris:streaming:v609"
    const val PLAYLIST_ROOT_ID = "polaris:playlists:v609"
    const val RADIO_TILE_PREFIX = "polaris:radio-tile:v609:"
    fun tileStationId(mediaId: String): String? = mediaId
        .takeIf { it.startsWith(RADIO_TILE_PREFIX) }
        ?.removePrefix(RADIO_TILE_PREFIX)
        ?.takeIf { find(it) != null }'''
fresh='''    const val RADIO_BROWSE_ROOT_ID = "polaris:radio:v610"
    const val STREAMING_BROWSE_ROOT_ID = "polaris:streaming:v610"
    const val PLAYLIST_ROOT_ID = "polaris:playlists:v610"
    const val RADIO_TILE_PREFIX = "polaris:radio-tile:v610:"
    // Keep the 6.09 cached IDs resolvable, but expose clean 6.10 browse nodes.
    const val RADIO_BROWSE_PREVIOUS_ROOT_ID = "polaris:radio:v609"
    const val STREAMING_BROWSE_PREVIOUS_ROOT_ID = "polaris:streaming:v609"
    const val PLAYLIST_PREVIOUS_ROOT_ID = "polaris:playlists:v609"
    private const val PREVIOUS_TILE_PREFIX = "polaris:radio-tile:v609:"
    fun tileStationId(mediaId: String): String? = (
        when {
            mediaId.startsWith(RADIO_TILE_PREFIX) -> mediaId.removePrefix(RADIO_TILE_PREFIX)
            mediaId.startsWith(PREVIOUS_TILE_PREFIX) -> mediaId.removePrefix(PREVIOUS_TILE_PREFIX)
            else -> null
        }
    )?.takeIf { find(it) != null }'''
if s.count(prior)!=1:raise AssertionError("6.10 previous catalog version missing")
catalog.write_text(s.replace(prior,fresh),encoding="utf-8")

callback=pkg/"playback/MediaLibrarySessionCallback.kt"
s=callback.read_text(encoding="utf-8")
# Reuse InterTune's default (unforced) presentation of browse shortcuts.
# 6.08's category-grid override provoked two huge rows of tiles on Polestar's
# portrait Android Auto host. Let the host render standard browse items instead.
old='''        val category = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
        val icons = listOf('''
new='''        // Match InterTune's native, compact category entry metadata; never
        // force the CATEGORY_GRID_ITEM style on this portrait AA browse level.
        val icons = listOf('''
if s.count(old)!=1:raise AssertionError("6.10 expected explicit category-grid flag")
s=s.replace(old,new,1)
pattern=r'(MediaMetadata\.MEDIA_TYPE_[A-Z_]+),\n\s+style\(single = category\)\)'
s,n=re.subn(pattern,r'\1)',s)
if n!=4:raise AssertionError(f"6.10 expected four forced category styles, got {n}")
# Keep playable songs in a list without instructing AA to use a category grid.
old='''                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)'''
new='''                    // Browsable children keep the original InterTune default
                    // style; unlike 6.09 they aren't forced into large tiles.
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)'''
if s.count(old)!=1:raise AssertionError("6.10 nested streaming parent grid expected once")
s=s.replace(old,new,1)

# Retain old browse pointers from 6.09 (and older stable canonical routes).
old="RadioCatalog.RADIO_BROWSE_ROOT_ID, RadioCatalog.RADIO_ROOT_ID ->"
new="RadioCatalog.RADIO_BROWSE_ROOT_ID, RadioCatalog.RADIO_BROWSE_PREVIOUS_ROOT_ID, RadioCatalog.RADIO_ROOT_ID ->"
if s.count(old)!=1:raise AssertionError("6.10 radio children route")
s=s.replace(old,new,1)
old="RadioCatalog.STREAMING_BROWSE_ROOT_ID, RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)"
new="RadioCatalog.STREAMING_BROWSE_ROOT_ID, RadioCatalog.STREAMING_BROWSE_PREVIOUS_ROOT_ID, RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)"
if s.count(old)!=1:raise AssertionError("6.10 streaming children route")
s=s.replace(old,new,1)
old="RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)"
new="RadioCatalog.PLAYLIST_ROOT_ID, RadioCatalog.PLAYLIST_PREVIOUS_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)"
if s.count(old)!=1:raise AssertionError("6.10 playlists children route")
s=s.replace(old,new,1)
old='''mediaId == RadioCatalog.STREAMING_ROOT_ID || mediaId == RadioCatalog.PLAYLIST_ROOT_ID ||
            mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID ||
            mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||'''
new='''mediaId == RadioCatalog.STREAMING_ROOT_ID || mediaId == RadioCatalog.PLAYLIST_ROOT_ID ||
            mediaId == RadioCatalog.PLAYLIST_PREVIOUS_ROOT_ID ||
            mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID ||
            mediaId == RadioCatalog.RADIO_BROWSE_PREVIOUS_ROOT_ID ||
            mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||
            mediaId == RadioCatalog.STREAMING_BROWSE_PREVIOUS_ROOT_ID ||'''
if s.count(old)!=1:raise AssertionError("6.10 folder onGetItem aliases")
s=s.replace(old,new,1)
for old,new in [
  ("id == RadioCatalog.RADIO_BROWSE_ROOT_ID) {",
   "id == RadioCatalog.RADIO_BROWSE_ROOT_ID ||\n                    id == RadioCatalog.RADIO_BROWSE_PREVIOUS_ROOT_ID) {"),
  ("mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID) \"Radio\"",
   "mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID ||\n                mediaId == RadioCatalog.RADIO_BROWSE_PREVIOUS_ROOT_ID) \"Radio\""),
  ("mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID) \"Streaming\"",
   "mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||\n                    mediaId == RadioCatalog.STREAMING_BROWSE_PREVIOUS_ROOT_ID) \"Streaming\""),
  ("else if (mediaId == RadioCatalog.PLAYLIST_ROOT_ID ||",
   "else if (mediaId == RadioCatalog.PLAYLIST_ROOT_ID ||\n                    mediaId == RadioCatalog.PLAYLIST_PREVIOUS_ROOT_ID ||"),
  ("id == RadioCatalog.PLAYLIST_ROOT_ID ||\n                    id == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||",
   "id == RadioCatalog.PLAYLIST_ROOT_ID ||\n                    id == RadioCatalog.PLAYLIST_PREVIOUS_ROOT_ID ||\n                    id == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||"),
  ("id == RadioCatalog.STREAMING_BROWSE_ROOT_ID || id == MusicService.PLAYLIST)",
   "id == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||\n                    id == RadioCatalog.STREAMING_BROWSE_PREVIOUS_ROOT_ID ||\n                    id == MusicService.PLAYLIST)"),
]:
    if s.count(old)!=1:raise AssertionError(f"6.10 media library alias {old!r} count={s.count(old)}")
    s=s.replace(old,new,1)
callback.write_text(s,encoding="utf-8")

provider=pkg/"polaris/PolarisRadioArtworkProvider.kt"
replace_exact(provider,'.appendPath("tile609")','.appendPath("tile610")',"radio tile cache version")
replace_exact(provider,'uri.pathSegments[0] !in listOf("tile", "tile609")',
              'uri.pathSegments[0] !in listOf("tile", "tile609", "tile610")',"radio provider compatibility")

# One-line station name/frequency was already printed inside the station logo.
# Use the reclaimed caption line to restore most of the original logo area.
tile=pkg/"polaris/PolarisRadioBrowseTile.kt"
s=tile.read_text(encoding="utf-8")
for old,new,label in [
 ('Rect(94, 6, 418, 330)','Rect(40, 0, 472, 432)','larger centered logo'),
 ('textSize = 35f','textSize = 28f','programme size for two full lines'),
 ('val lineWidth = 488','val lineWidth = 496','wider title'),
 ('canvas.translate((SIZE - lineWidth) / 2f, 339f)',
  'canvas.translate((SIZE - lineWidth) / 2f, 442f)','programme under logo'),
]:
    if s.count(old)!=1:raise AssertionError(f"6.10 art {label}: {s.count(old)}")
    s=s.replace(old,new,1)
start=s.index('''        val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {''')
end=s.index('''        return result''',start)
removed=s[start:end]
assert 'val caption =' in removed and 'canvas.drawText(caption,' in removed
s=s[:start]+s[end:]
tile.write_text(s,encoding="utf-8")

# The 6.08 regression asserted that large category tiles were requested.
# That earlier expectation is explicitly superseded by the 6.10 fix.
oldtest=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisAA608Test.kt"
oldcode=oldtest.read_text(encoding="utf-8")
needle='assertTrue(callback.contains("style(single = category)"))'
assert oldcode.count(needle)==1
oldtest.write_text(oldcode.replace(needle,
    'assertTrue(!callback.contains("style(single = category)"))',1),encoding="utf-8")

# The 6.09 assertion hard-coded a single playlist route, while 6.10
# explicitly supports both v609 and v610 IDs. Adapt the source-level test.
oldtest609=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisAA609Test.kt"
oldcode609=oldtest609.read_text(encoding="utf-8")
assert oldcode609.count("RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)")==1
oldtest609.write_text(oldcode609.replace(
    "RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)",
    "RadioCatalog.PLAYLIST_ROOT_ID, RadioCatalog.PLAYLIST_PREVIOUS_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)",1),encoding="utf-8")

# Update prior source-only assertions so 6.10's intentionally removed
# duplicated station caption is not called a regression.
for filename in ["PolarisAA608Test.kt","PolarisAA609Test.kt"]:
    t=root/"app/src/test/java/com/dd3boh/outertune/polaris"/filename
    x=t.read_text(encoding="utf-8")
    for needle in ['assertTrue(tile.contains("Paint.Align.CENTER"))',
                   'assertTrue(art.contains("Paint.Align.CENTER"))']:
        x=x.replace(needle,'assertTrue(!' + ('tile' if 'tile.' in needle else 'art') + '.contains("canvas.drawText(caption,"))')
    t.write_text(x,encoding="utf-8")

testroot=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris"
browser=testroot/"PolarisAA609BrowserTest.kt"
b=browser.read_text(encoding="utf-8")
assert b.count("v609")>=3
b=b.replace("v609","v610").replace('"tile609"','"tile610"')
browser.write_text(b,encoding="utf-8")
arttest=testroot/"PolarisAA609ArtworkTest.kt"
t=arttest.read_text(encoding="utf-8")
assert t.count('"/tile609/kr1/abcdef12"')==1
t=t.replace('"/tile609/kr1/abcdef12"','"/tile610/kr1/abcdef12"')
t=t.replace('assertEquals("tile609", uri.pathSegments.firstOrNull())',
            'assertEquals("tile610", uri.pathSegments.firstOrNull())')
arttest.write_text(t,encoding="utf-8")
session=testroot/"PolarisSessionTest.kt"
x=session.read_text(encoding="utf-8")
assert x.count("Polaris 6.09 (versionCode 100609)")==1
session.write_text(x.replace("Polaris 6.09 (versionCode 100609)",
                             "Polaris 6.10 (versionCode 100610)",1),encoding="utf-8")

build=root/"app/build.gradle.kts"
x=build.read_text(encoding="utf-8")
assert x.count("versionCode = 100609")==1 and x.count('versionName = "6.09"')==1
build.write_text(x.replace("versionCode = 100609","versionCode = 100610",1)
                   .replace('versionName = "6.09"','versionName = "6.10"',1),encoding="utf-8")
print("PASS 6.10: standard browse categories + list, enlarged centered-logo programme-only tile, cache v610, old IDs and playback intact")


# A real MediaBrowser runtime check confirms that the four category shortcuts
# are present but no longer carry the explicit large CATEGORY_GRID_ITEM hint.
compact=testroot/"PolarisAA610CompactTest.kt"
compact.write_text("""package com.dd3boh.outertune.polaris

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.playback.MusicService
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PolarisAA610CompactTest {
    @Test fun realStreamingCategoriesAreUnforcedBrowsableShortcuts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var connected: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            connected = MediaBrowser.Builder(context,
                SessionToken(context, ComponentName(context, MusicService::class.java))
            ).buildAsync()
        }
        try {
            val browser = connected.get(25, TimeUnit.SECONDS)
            val pending = instrumentation.run {
                lateinit var list: com.google.common.util.concurrent.ListenableFuture<
                    androidx.media3.session.LibraryResult<
                        com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
                runOnMainSync { list = browser.getChildren("polaris:streaming:v610", 0, 25, null) }
                list
            }
            val items = pending.get(35, TimeUnit.SECONDS).value.orEmpty()
            assertTrue("Expected four InterTune browse shortcuts", items.size >= 4)
            assertEquals(listOf(MusicService.SONG, MusicService.ARTIST,
                MusicService.ALBUM, "polaris:playlists:v610"), items.take(4).map { it.mediaId })
            items.take(4).forEach {
                assertTrue(it.mediaMetadata.isBrowsable)
                assertFalse("No forced large-tile style", it.mediaMetadata.extras?.containsKey(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM) == true)
            }
        } finally {
            instrumentation.runOnMainSync { MediaController.releaseFuture(connected) }
        }
    }
}
""",encoding="utf-8")

print("PASS 6.10 native category shortcut test injected")
