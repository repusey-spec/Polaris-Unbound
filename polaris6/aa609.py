#!/usr/bin/env python3
"""6.09: keep radio/stream playback, repair both AA browse entry points and radio tile cache.
This applies after 6.08; no music engine, DB, account, radio URL or phone UI changes.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"

def edit(rel, actions):
    path=pkg/rel
    s=path.read_text(encoding="utf-8")
    for name,old,new in actions:
        n=s.count(old)
        if n!=1: raise RuntimeError(f"609 {rel}: {name} expected once, got {n}: {old[:100]}")
        s=s.replace(old,new,1)
    path.write_text(s,encoding="utf-8")

edit("polaris/RadioCatalog.kt",[
    ("new root IDs",
    '    const val RADIO_ROOT_ID = "polaris:radio"\n    const val STREAMING_ROOT_ID = "polaris:streaming"\n',
    '''    // Versioned browser IDs intentionally break stale Android Auto cached nodes.
    // Old public IDs must be stable (voice integration and saved bookmarks).
    const val RADIO_ROOT_ID = "polaris:radio"
    const val STREAMING_ROOT_ID = "polaris:streaming"
    // Only the fresh AA tabs use versioned IDs to avoid stale cached browse nodes.
    const val RADIO_BROWSE_ROOT_ID = "polaris:radio:v609"
    const val STREAMING_BROWSE_ROOT_ID = "polaris:streaming:v609"
    const val PLAYLIST_ROOT_ID = "polaris:playlists:v609"
    const val RADIO_TILE_PREFIX = "polaris:radio-tile:v609:"
    fun tileStationId(mediaId: String): String? = mediaId
        .takeIf { it.startsWith(RADIO_TILE_PREFIX) }
        ?.removePrefix(RADIO_TILE_PREFIX)
        ?.takeIf { find(it) != null }
    '''.rstrip()+"\n")
])

edit("playback/MediaLibrarySessionCallback.kt",[
    ("new AA top-level browse IDs without changing stable radio IDs",
    '''                    polarisFolder(RadioCatalog.RADIO_ROOT_ID, "Radio"),
                    polarisFolder(RadioCatalog.STREAMING_ROOT_ID, "Streaming"),''',
    '''                    polarisFolder(RadioCatalog.RADIO_BROWSE_ROOT_ID, "Radio"),
                    polarisFolder(RadioCatalog.STREAMING_BROWSE_ROOT_ID, "Streaming"),'''),
    ("log actual parent IDs",
    '''        val own = browser.packageName == context.packageName
        val bar =''',
    '''        val own = browser.packageName == context.packageName
        if (parentId == MusicService.ROOT || parentId.startsWith("polaris:") ||
            parentId == MusicService.PLAYLIST) {
            PolarisErrorReport.record("aa609-children parent=" + parentId +
                " browser=" + browser.packageName + " page=" + page)
        }
        val bar ='''),
    ("radio old root compatibility",
    '''                RadioCatalog.RADIO_ROOT_ID -> CarSearch.page(PolarisRadioLayout(context).slots().filterNotNull().map { polarisRadioTile(it) }, page, pageSize)''',
    '''                RadioCatalog.RADIO_BROWSE_ROOT_ID, RadioCatalog.RADIO_ROOT_ID ->
                    CarSearch.page(PolarisRadioLayout(context).slots().filterNotNull().map { polarisRadioTile(it) }, page, pageSize)'''),
    ("streaming old root compatibility",
    '''                RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)''',
    '''                RadioCatalog.STREAMING_BROWSE_ROOT_ID, RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)'''),
    ("library fallback to exact new home; category opens real playlists",
    '''                MusicService.PLAYLIST -> polarisPlaylistRows()''',
    '''                // Some AA hosts retain the original InterTune 'playlist' node
                // across an app update. It must show the SAME landing as Streaming.
                MusicService.PLAYLIST -> polarisStreamingHome(page, pageSize)
                RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)'''),
    ("station browser id not shared with cached player",
    '''        return item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon()
                .setTitle("\\u200B")''',
    '''        return item.buildUpon()
            .setMediaId(RadioCatalog.RADIO_TILE_PREFIX + id)
            .setMediaMetadata(
            item.mediaMetadata.buildUpon()
                .setTitle("\\u200B")'''),
    ("category playlist shortcut target",
    '''            browsableMediaItem(MusicService.PLAYLIST, context.getString(R.string.playlists), null,''',
    '''            browsableMediaItem(RadioCatalog.PLAYLIST_ROOT_ID, context.getString(R.string.playlists), null,'''),
    ("folder onGetItem alias",
    '''        if (mediaId == MusicService.ROOT || mediaId == RadioCatalog.RADIO_ROOT_ID || mediaId == RadioCatalog.STREAMING_ROOT_ID) {''',
    '''        if (mediaId == MusicService.ROOT || mediaId == RadioCatalog.RADIO_ROOT_ID ||
            mediaId == RadioCatalog.STREAMING_ROOT_ID || mediaId == RadioCatalog.PLAYLIST_ROOT_ID ||
            mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID ||
            mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID ||
            mediaId == MusicService.PLAYLIST) {'''),
    ("onGetItem radio alias",
    '''        if (stationId != null) return@future LibraryResult.ofItem(polarisRadioTile(stationId), null)''',
    '''        if (stationId != null) return@future LibraryResult.ofItem(PolarisRadioRequests.queueItem(stationId), null)
        RadioCatalog.tileStationId(mediaId)?.let {
            PolarisErrorReport.record("aa609-item tile=" + it)
            return@future LibraryResult.ofItem(polarisRadioTile(it), null)
        }'''),
    ("radio browse root style remains a grid",
    '''                if (id == RadioCatalog.RADIO_ROOT_ID) {''',
    '''                if (id == RadioCatalog.RADIO_ROOT_ID ||
                    id == RadioCatalog.RADIO_BROWSE_ROOT_ID) {'''),
    ("onGetItem returns the right root labels",
    '''if(mediaId == RadioCatalog.RADIO_ROOT_ID) "Radio" else if(mediaId == RadioCatalog.STREAMING_ROOT_ID) "Streaming" else "Polaris"''',
    '''if (mediaId == RadioCatalog.RADIO_ROOT_ID ||
                mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID) "Radio"
                else if (mediaId == RadioCatalog.STREAMING_ROOT_ID ||
                    mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID) "Streaming"
                else if (mediaId == RadioCatalog.PLAYLIST_ROOT_ID ||
                    mediaId == MusicService.PLAYLIST) context.getString(R.string.playlists)
                else "Polaris"'''),
    ("root style playlist browser new id",
    '''                } else if (id == RadioCatalog.STREAMING_ROOT_ID) {''',
    '''                } else if (id == RadioCatalog.STREAMING_ROOT_ID ||
                    id == RadioCatalog.PLAYLIST_ROOT_ID ||
                    id == RadioCatalog.STREAMING_BROWSE_ROOT_ID || id == MusicService.PLAYLIST) {'''),
    ("play tile by routing to canonical live radio queue",
    '''        val radioId = PolarisRadioRequests.stationId(request)
            ?: request.requestMetadata.searchQuery?.let(PolarisVoiceRouter::radioIdForQuery)''',
    '''        val radioId = PolarisRadioRequests.stationId(request)
            ?: RadioCatalog.tileStationId(request.mediaId)
            ?: request.requestMetadata.searchQuery?.let(PolarisVoiceRouter::radioIdForQuery)'''),
])
edit("playback/MusicService.kt",[
    ("notify both stable and fresh AA radio roots when current programme changes",
    '''mediaSession.notifyChildrenChanged(RadioCatalog.RADIO_ROOT_ID, Int.MAX_VALUE, null)''',
    '''mediaSession.notifyChildrenChanged(RadioCatalog.RADIO_ROOT_ID, Int.MAX_VALUE, null)
                mediaSession.notifyChildrenChanged(RadioCatalog.RADIO_BROWSE_ROOT_ID, Int.MAX_VALUE, null)'''),
])

# Radio item callback in 6.08's onGetItem only checks canonical 'polaris:station:';
# to distinguish old/stale leaf calls, onGetItem alias is explicitly supported.

edit("polaris/PolarisRadioArtworkProvider.kt",[
    ("invalidate 6.08 content URI and Android Auto image cache",
    '''            .appendPath("tile").appendPath(id).appendPath(version).build()''',
    '''            .appendPath("tile609").appendPath(id).appendPath(version).build()'''),
    ("provider recognizes new tile format",
    '''            uri.pathSegments.size != 3 || uri.pathSegments[0] != "tile" ||''',
    '''            uri.pathSegments.size != 3 ||
            uri.pathSegments[0] !in listOf("tile", "tile609") ||'''),
    ("separate tile cache files for new version",
    '''            else File(dir, "browse-" + stationId + "-" + uri.pathSegments[2] + ".png")''',
    '''            else File(dir, "browse-" + uri.pathSegments[0] + "-" +
                stationId + "-" + uri.pathSegments[2] + ".png")'''),
    ("report real host artwork fetches",
    '''        val tile = tileId(uri)
        val stationId = tile ?: validate(uri)''',
    '''        val tile = tileId(uri)
        val stationId = tile ?: validate(uri)
        PolarisErrorReport.record("aa609-artwork path=" +
            uri.pathSegments.firstOrNull() + " station=" + stationId)'''),
])

# On the real AA grid the composite image is rescaled much smaller. Allocate
# more of the square to legible centered captions while preserving source logo.
edit("polaris/PolarisRadioBrowseTile.kt",[
    ("more room for readable captions",
    '''        canvas.drawBitmap(logo, null, Rect(62, 2, 450, 390),''',
    '''        canvas.drawBitmap(logo, null, Rect(94, 6, 418, 330),'''),
    ("slightly larger programme text",
    '''            textSize = 30f''',
    '''            textSize = 35f'''),
    ("text starts under the logo",
    '''        canvas.translate((SIZE - lineWidth) / 2f, 392f)''',
    '''        canvas.translate((SIZE - lineWidth) / 2f, 339f)'''),
    ("slightly larger station name",
    '''            textSize = 27f''',
    '''            textSize = 32f'''),
    ("station text baseline",
    '''        canvas.drawText(caption, SIZE / 2f, 501f, stationPaint)''',
    '''        canvas.drawText(caption, SIZE / 2f, 492f, stationPaint)'''),
])

# Stable update identity; do NOT modify existing DB/settings names or signer.
gradle=root/"app/build.gradle.kts"
s=gradle.read_text(encoding="utf-8")
assert s.count("versionCode = 100608")==1 and s.count('versionName = "6.08"')==1
gradle.write_text(s.replace("versionCode = 100608","versionCode = 100609",1)
    .replace('versionName = "6.08"','versionName = "6.09"',1),encoding="utf-8")

report=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
s=report.read_text(encoding="utf-8")
assert s.count("Polaris 6.08 (versionCode 100608)")==1
report.write_text(s.replace("Polaris 6.08 (versionCode 100608)",
    "Polaris 6.09 (versionCode 100609)",1),encoding="utf-8")

# Update source-level 6.06/6.08 assertions for the intentional aliases.
for name, pairs in [
 ("PolarisAA606Test.kt",[
  ('MusicService.PLAYLIST -> polarisPlaylistRows()',
   'RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)'),
 ]),
 ("PolarisAA608Test.kt",[
  ('MusicService.PLAYLIST, context.getString(R.string.playlists)',
   'RadioCatalog.PLAYLIST_ROOT_ID, context.getString(R.string.playlists)'),
 ]),
    ("PolarisAA606Test.kt",[
  ('RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)',
   'RadioCatalog.STREAMING_BROWSE_ROOT_ID,'),
 ]),
 ("PolarisAA608Test.kt",[
  ('RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)',
   'RadioCatalog.STREAMING_BROWSE_ROOT_ID,'),
 ]),
]:
    path=root/"app/src/test/java/com/dd3boh/outertune/polaris"/name
    s=path.read_text(encoding="utf-8")
    for old,new in pairs:
        assert s.count(old)==1, f"old {name}: {old}"
        s=s.replace(old,new,1)
    path.write_text(s,encoding="utf-8")

t=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisAA609Test.kt"
t.write_text('''package com.dd3boh.outertune.polaris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolarisAA609Test {
    private val root = "src/main/java/com/dd3boh/outertune/"
    private val tree = File(root + "playback/MediaLibrarySessionCallback.kt").readText()
    private val provider = File(root + "polaris/PolarisRadioArtworkProvider.kt").readText()
    private val art = File(root + "polaris/PolarisRadioBrowseTile.kt").readText()

    @Test fun streamingFallbackAndNewLandingAreExactlyTheSame() {
        assertTrue(tree.contains("RadioCatalog.STREAMING_BROWSE_ROOT_ID,"))
        assertTrue(tree.contains("polaris:streaming"))
        assertTrue(tree.contains("MusicService.PLAYLIST -> polarisStreamingHome(page, pageSize)"))
        assertTrue(tree.contains("RadioCatalog.PLAYLIST_ROOT_ID -> CarSearch.page(polarisPlaylistRows(), page, pageSize)"))
        assertTrue(tree.contains("browsableMediaItem(RadioCatalog.PLAYLIST_ROOT_ID"))
        assertTrue(tree.contains("CarSearch.page(icons + rows, page, pageSize)"))
    }
    @Test fun newRadioTileIdArtAndPlaybackAreConsistent() {
        assertEquals("kr1", RadioCatalog.tileStationId("polaris:radio-tile:v609:kr1"))
        assertNull(RadioCatalog.tileStationId("polaris:station:kr1"))
        assertNull(RadioCatalog.tileStationId("polaris:radio-tile:v609:unlisted"))
        assertTrue(tree.contains("setMediaId(RadioCatalog.RADIO_TILE_PREFIX + id)"))
        assertTrue(tree.contains("RadioCatalog.tileStationId(request.mediaId)"))
        assertTrue(tree.contains("RadioCatalog.tileStationId(mediaId)?.let"))
        assertTrue(provider.contains("tile609"))
        assertTrue(provider.contains("PolarisRadioBrowseTile.render(ctx, stationId)"))
        assertTrue(art.contains("Layout.Alignment.ALIGN_CENTER"))
        assertTrue(art.contains("Paint.Align.CENTER"))
    }
}
''',encoding="utf-8")

# Unlike earlier source-string tests, this runs the real ContentProvider on
# Android 13, reads the versioned URI and decodes the actual composite bitmap.
instrumented=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisAA609ArtworkTest.kt"
instrumented.write_text("""package com.dd3boh.outertune.polaris

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolarisAA609ArtworkTest {
    @Test fun actualProviderReturnsNewCenteredTileImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PolarisRadioMetadata.install(context)
        val uri = PolarisRadioArtwork.browseTileUri(
            "kr1", "89.1 KBS CoolFM", "현재 프로그램"
        )
        assertEquals("tile609", uri.pathSegments.firstOrNull())
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("Radio tile content provider must return a real image", bitmap)
        assertEquals(512, bitmap!!.width)
        assertEquals(512, bitmap.height)
        val old = PolarisRadioArtwork.uri("kr1")
        assertTrue(uri != old)
        val station = context.contentResolver.openInputStream(old)?.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(station)
    }
}
""",encoding="utf-8")

# Test the *executed* MediaLibraryService browse results, not merely the
# presence of "polarisStreamingHome" in the Kotlin source.
browser_test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisAA609BrowserTest.kt"
browser_test.write_text("""package com.dd3boh.outertune.polaris

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.playback.MusicService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PolarisAA609BrowserTest {
    @Test fun actualMediaBrowserGetsCategoriesAndStationTile609() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val future = MediaBrowser.Builder(context,
            SessionToken(context, ComponentName(context, MusicService::class.java))
        ).buildAsync()
        try {
            val browser = future.get(25, TimeUnit.SECONDS)
            val root = browser.getChildren(MusicService.ROOT, 0, 10, null)
                .get(25, TimeUnit.SECONDS).value?.toList().orEmpty()
            assertEquals(listOf(RadioCatalog.RADIO_BROWSE_ROOT_ID, RadioCatalog.STREAMING_BROWSE_ROOT_ID),
                root.map { it.mediaId })

            val categories = listOf(MusicService.SONG, MusicService.ARTIST,
                MusicService.ALBUM, RadioCatalog.PLAYLIST_ROOT_ID)
            for (parent in listOf(RadioCatalog.STREAMING_BROWSE_ROOT_ID, MusicService.PLAYLIST)) {
                val actual = browser.getChildren(parent, 0, 20, null)
                    .get(35, TimeUnit.SECONDS).value?.toList().orEmpty()
                assertTrue("Streaming root empty for " + parent, actual.size >= 4)
                assertEquals(categories, actual.take(4).map { it.mediaId })
            }
            val radio = browser.getChildren(RadioCatalog.RADIO_BROWSE_ROOT_ID, 0, 8, null)
                .get(25, TimeUnit.SECONDS).value?.toList().orEmpty()
            assertTrue(radio.isNotEmpty())
            assertTrue(radio[0].mediaId.startsWith(RadioCatalog.RADIO_TILE_PREFIX))
            assertEquals("tile609",
                radio[0].mediaMetadata.artworkUri?.pathSegments?.firstOrNull())
        } finally {
            MediaController.releaseFuture(future)
        }
    }
}
""",encoding="utf-8")

print("PASS: 6.09 AA old+new streaming routes, category playlist, radio browse cache/version, image and playback alias")
