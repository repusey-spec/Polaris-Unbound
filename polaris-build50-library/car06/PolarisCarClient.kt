package app.hush.music.polaris

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.LruCache
import androidx.car.app.CarContext
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import app.hush.music.playback.MusicService
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** The new car UI connects to the existing Hush session; it owns no audio player. */
interface PolarisCarCatalog {
    val connected: Boolean
    val connectionError: String?
    val currentId: String?
    val changes: MutableStateFlow<Long>
    suspend fun children(parent: String, page: Int, size: Int): List<MediaItem>
    suspend fun search(query: String, size: Int): List<MediaItem>
    suspend fun play(item: MediaItem)
    fun icon(item: MediaItem): CarIcon? = null
    suspend fun loadIcons(items: List<MediaItem>) {}
}

class PolarisCarClient(private val context: CarContext) : PolarisCarCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var browser: MediaBrowser? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>? = null
    private var registered = false
    override var connectionError: String? = null
        private set
    override val connected get() = browser != null && registered
    override val currentId get() = browser?.currentMediaItem?.mediaId
    override val changes = MutableStateFlow(0L)
    private val icons = LruCache<String, CarIcon>(64)
    private val iconPermits = Semaphore(4)
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { changed() }
    }
    private fun changed() { changes.value += 1L }

    fun connect() {
        if (future != null) return
        connectionError = null
        scope.launch {
            try {
                val connection = MediaBrowser.Builder(context,
                    SessionToken(context, ComponentName(context, MusicService::class.java))).buildAsync()
                future = connection
                val value = connection.await()
                browser = value
                value.addListener(listener)
                val result = value.sendCustomCommand(
                    SessionCommand(PolarisCarNavigation.TOKEN_COMMAND, Bundle.EMPTY), Bundle.EMPTY).await()
                if (result.resultCode != SessionResult.RESULT_SUCCESS) throw IOException("Session token unavailable")
                @Suppress("DEPRECATION")
                val token = result.extras.getParcelable<android.media.session.MediaSession.Token>(PolarisCarNavigation.TOKEN_KEY)
                    ?: throw IOException("Session token missing")
                val compat = requireNotNull(MediaSessionCompat.Token.fromToken(token))
                context.getCarService(MediaPlaybackManager::class.java).registerMediaPlaybackToken(compat)
                registered = true
                PolarisErrorReport.record("car06 connected API=" + context.carAppApiLevel + " token=registered")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionError = "재생 서비스 연결 실패 · 다시 실행해 주세요"
                PolarisErrorReport.failure("car06-connect", error)
            } finally { changed() }
        }
    }

    override suspend fun children(parent: String, page: Int, size: Int): List<MediaItem> {
        val result = requireNotNull(browser).getChildren(parent, page, size, null).await()
        if (result.resultCode != 0) throw IOException("Browse failed code=${result.resultCode} parent=$parent")
        return result.value?.toList().orEmpty()
    }

    override suspend fun search(query: String, size: Int): List<MediaItem> {
        val value = requireNotNull(browser)
        val request = value.search(query, null).await()
        if (request.resultCode != 0) throw IOException("Search failed code=${request.resultCode}")
        val result = value.getSearchResult(query, 0, size, null).await()
        if (result.resultCode != 0) throw IOException("Search results failed code=${result.resultCode}")
        PolarisErrorReport.record("car06 search engine=Hush-YouTubeMusic count=" + (result.value?.size ?: 0))
        return result.value?.toList().orEmpty()
    }

    override suspend fun play(item: MediaItem) {
        require(item.mediaMetadata.isPlayable == true) { "Cannot play a browse folder" }
        val value = requireNotNull(browser)
        // All preparation still passes through 50.5's existing onSetMediaItems.
        value.setMediaItem(item)
        value.prepare()
        value.play()
        PolarisErrorReport.record("car06 user-selected " + PolarisErrorReport.item(item))
    }

    override fun icon(item: MediaItem): CarIcon? =
        item.mediaMetadata.artworkUri?.toString()?.let(icons::get)

    override suspend fun loadIcons(items: List<MediaItem>) = coroutineScope {
        items.mapNotNull { it.mediaMetadata.artworkUri }.distinct().map { uri ->
            launch {
                val key = uri.toString()
                if (icons.get(key) != null) return@launch
                iconPermits.withPermit {
                    try {
                        val request = ImageRequest.Builder(context.applicationContext).data(uri)
                            .allowHardware(false).size(96, 96).build()
                        val result = context.applicationContext.imageLoader.execute(request)
                        if (result is SuccessResult) {
                            val bitmap = result.image.toBitmap()
                            icons.put(key, CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
                            changed()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A missing thumbnail must not hide a list. */ }
                }
            }
        }.joinAll()
    }

    fun close() {
        scope.cancel()
        browser?.removeListener(listener)
        browser?.release()
        if (browser == null) future?.let { MediaController.releaseFuture(it) }
        browser = null
        registered = false
        icons.evictAll()
    }
}
