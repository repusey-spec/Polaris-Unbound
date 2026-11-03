package app.hush.music.polaris

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import app.hush.music.extensions.toMediaItem
import app.hush.music.extensions.toQueue
import app.hush.music.models.MediaMetadata
import app.hush.music.models.PersistQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PolarisRadioRegressionTest {
    @Test fun persistedRadioMetadataNeverBecomesPolarisHttpUrl() {
        val item = MediaMetadata("polaris:station:kr2", "Radio", emptyList(), -1).toMediaItem()
        assertEquals("polaris:station:kr2", item.mediaId)
        assertEquals(PolarisRadioRequests.SCHEME, item.localConfiguration!!.uri.scheme)
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration!!.mimeType)
        assertNull(item.localConfiguration!!.customCacheKey)
    }

    @Test fun actualPersistentQueueConversionRestoresResolvableRadio() = runBlocking {
        val persisted = PersistQueue("Radio", listOf(
            MediaMetadata("polaris:station:kr2", "Radio", emptyList(), -1)
        ), 0, 123456L)
        val restored = persisted.toQueue().getInitialStatus()
        val item = restored.items[restored.mediaItemIndex]
        assertEquals(PolarisRadioRequests.SCHEME, item.localConfiguration!!.uri.scheme)
        val queue = PolarisRadioPlaylist.selection(RuntimeEnvironment.getApplication(), item)
        assertEquals(13, queue.items.size)
        assertEquals("polaris:station:kr2", queue.items[queue.index].mediaId)
    }

    @Test fun oldCorruptedItemsWithLocalConfigurationAreRepaired() {
        val bad = MediaItem.Builder().setMediaId("polaris:station:kr2")
            .setUri("polaris:station:kr2").setCustomCacheKey("polaris:station:kr2").build()
        val repaired = PolarisRadioRequests.normalize(bad)
        assertEquals(PolarisRadioRequests.SCHEME, repaired.localConfiguration!!.uri.scheme)
        assertNull(repaired.localConfiguration!!.customCacheKey)
    }

    @Test fun queueContainsEveryStationAndKeepsSelectedStation() {
        val context = RuntimeEnvironment.getApplication()
        PolarisRadioLayout(context).swap(0, "kr2")
        val selected = PolarisRadioPlayback.mediaItem("kr2", "https://audio.example/live.m3u8")
        val queue = PolarisRadioPlaylist.selection(context, selected)
        assertEquals(13, queue.items.size)
        assertEquals(13, queue.items.map { it.mediaId }.toSet().size)
        assertEquals(selected.mediaId, queue.items[queue.index].mediaId)
        assertEquals(selected.localConfiguration!!.uri, queue.items[queue.index].localConfiguration!!.uri)
        assertEquals("polaris:station:kr2", queue.items.first().mediaId)
    }

    @Test fun lazyStationIsResolvedBeforeHttpAndChildrenRemainUnchanged() {
        val item = PolarisRadioRequests.queueItem("kr2")
        val request = DataSpec.Builder().setUri(item.localConfiguration!!.uri).build()
        val resolved = PolarisRadioRequests.resolve(request) { id ->
            assertEquals("kr2", id)
            "https://audio.example/live/master.m3u8?token=test"
        }
        assertEquals("https", resolved.uri.scheme)
        assertNull(resolved.key)
        val segment = DataSpec.Builder().setUri("https://audio.example/live/seg001.aac").setPosition(20L).build()
        val unchanged = PolarisRadioRequests.resolve(segment) { fail("Do not resolve HLS children"); "" }
        assertEquals(segment.uri, unchanged.uri)
        assertEquals(20L, unchanged.position)
    }

    @Test fun malformedAndNonHttpResolvedAddressesNeverReachHttp() {
        for (value in listOf("polaris:station:kr2", "file:///bad", "polaris-radio://station/unknown")) {
            try {
                PolarisRadioRequests.resolve(DataSpec.Builder().setUri(value).build())
                fail("Expected rejection: $value")
            } catch (_: IOException) { }
        }
        try {
            PolarisRadioRequests.resolve(DataSpec.Builder().setUri("polaris-radio://station/kr2").build()) { "polaris:station:kr2" }
            fail("Invalid resolver output must fail")
        } catch (_: IOException) { }
    }

    @Test fun musicMetadataRetainsOriginalHushResolverContract() {
        val item = MediaMetadata("abcdefghijk", "Song", emptyList(), 180).toMediaItem()
        assertEquals("abcdefghijk", item.localConfiguration!!.uri.toString())
        assertEquals("abcdefghijk", item.localConfiguration!!.customCacheKey)
    }
}
