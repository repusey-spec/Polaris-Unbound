package app.hush.music.polaris

import android.os.Looper
import androidx.car.app.HandshakeInfo
import androidx.car.app.OnDoneCallback
import androidx.car.app.ScreenManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.*
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PolarisCarNavigationTest {
    private lateinit var context: TestCarContext
    private lateinit var catalog: FakeCatalog
    private lateinit var manager: ScreenManager
    private val done = object : OnDoneCallback {}
    class FakeCatalog : PolarisCarCatalog {
        override var connected = true
        override val connectionError: String? = null
        override var currentId: String? = "polaris:station:kr2"
        override val changes = MutableStateFlow(0L)
        val plays = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val searches = mutableListOf<String>()
        var items = emptyList<MediaItem>()
        override suspend fun children(parent: String, page: Int, size: Int): List<MediaItem> {
            requests += parent
            return items.drop(page * size).take(size)
        }
        override suspend fun search(query: String, size: Int): List<MediaItem> {
            searches += query; return items.take(size)
        }
        override suspend fun play(item: MediaItem) { plays += item.mediaId; currentId = item.mediaId }
    }
    @Before fun before() {
        context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        context.updateHandshakeInfo(HandshakeInfo("test.host", 8))
        context.lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        catalog = FakeCatalog()
        manager = context.getCarService(ScreenManager::class.java)
    }
    @After fun after() { context.lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    private fun click(action: Action) {
        requireNotNull(action.onClickDelegate).sendClick(done); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun item(id: String, folder: Boolean) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Test item")
            .setIsBrowsable(folder).setIsPlayable(!folder).build()).build()
    private fun playback(): MediaPlaybackTemplate {
        val screen = PolarisCarPlaybackScreen(context, catalog)
        manager.push(screen)
        return screen.onGetTemplate() as MediaPlaybackTemplate
    }
    @Test fun radioListButtonOpensStreamingWithoutChangingPlayback() {
        click(requireNotNull(playback().header).endHeaderActions.first())
        assertEquals(PolarisCarNavigation.STREAMING, (manager.top as PolarisCarBrowseScreen).parent)
        assertEquals("polaris:station:kr2", catalog.currentId)
        assertTrue(catalog.plays.isEmpty())
    }
    @Test fun streamingListButtonOpensRadioWithoutChangingPlayback() {
        catalog.currentId = "u2RdlY-X9UU"
        click(requireNotNull(playback().header).endHeaderActions.first())
        assertEquals(PolarisCarNavigation.RADIO, (manager.top as PolarisCarBrowseScreen).parent)
        assertEquals("u2RdlY-X9UU", catalog.currentId)
        assertTrue(catalog.plays.isEmpty())
    }
    @Test fun buttonUsesCurrentModeAtClickTimeRatherThanOldHeaderState() {
        val template = playback()
        catalog.currentId = "abcdefghijk"
        click(requireNotNull(template.header).endHeaderActions.first())
        assertEquals(PolarisCarNavigation.RADIO, (manager.top as PolarisCarBrowseScreen).parent)
        assertTrue(catalog.plays.isEmpty())
    }
    @Test fun libraryFolderClickOnlyNavigates() {
        catalog.items = listOf(item("playlist/LP_LIKED", true))
        val screen = PolarisCarBrowseScreen(context, catalog, PolarisCarNavigation.STREAMING, "보관함")
        manager.push(screen); screen.onGetTemplate(); shadowOf(Looper.getMainLooper()).idle()
        val template = screen.onGetTemplate() as ListTemplate
        (requireNotNull(template.singleList).items.first() as Row).onClickDelegate!!.sendClick(done)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("playlist/LP_LIKED", (manager.top as PolarisCarBrowseScreen).parent)
        assertTrue(catalog.plays.isEmpty())
    }
    @Test fun selectingPlayableItemUsesSameCatalogAndShowsPlayback() {
        catalog.items = listOf(item("abcdefghijk", false))
        val screen = PolarisCarBrowseScreen(context, catalog, "playlist/test", "Playlist")
        manager.push(screen); screen.onGetTemplate(); shadowOf(Looper.getMainLooper()).idle()
        val template = screen.onGetTemplate() as ListTemplate
        (requireNotNull(template.singleList).items.first() as Row).onClickDelegate!!.sendClick(done)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("abcdefghijk"), catalog.plays)
        assertTrue(manager.top is PolarisCarPlaybackScreen)
    }
    @Test fun searchTemplateSubmitsToOwnCatalogWithoutLaunchingAnotherApp() {
        catalog.items = listOf(item("abcdefghijk", false))
        val screen = PolarisCarSearchScreen(context, catalog)
        manager.push(screen)
        val template = screen.onGetTemplate() as SearchTemplate
        template.searchCallbackDelegate!!.sendSearchSubmitted("test song", done)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("test song"), catalog.searches)
        assertTrue(catalog.plays.isEmpty())
        assertTrue(context.startCarAppIntents.isEmpty())
        assertEquals(1, ((screen.onGetTemplate() as SearchTemplate).itemList!!.items.size))
    }
    @Test fun playbackWaitsForSessionRegistrationInsteadOfReturningInvalidTemplate() {
        catalog.connected = false
        val screen = PolarisCarPlaybackScreen(context, catalog)
        manager.push(screen)
        assertTrue(screen.onGetTemplate() is MessageTemplate)
        assertTrue(catalog.plays.isEmpty())
    }
}
