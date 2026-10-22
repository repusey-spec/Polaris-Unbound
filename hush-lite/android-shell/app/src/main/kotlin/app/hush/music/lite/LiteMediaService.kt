package app.hush.music.lite

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.hush.music.innertube.models.YouTubeLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Hush Lite native Android music session for core/AA integration testing.
 * Only one exported MediaLibraryService and one ExoPlayer.
 * No official YouTube Music app and no separate installed Hush required.
 *
 * IMPORTANT: The direct-stream probe is NOT yet Hush's complete URL resolver.
 * Compilation/AA visibility must not be confused with successful playback.
 */
@OptIn(UnstableApi::class)
class LiteMediaService : MediaLibraryService() {
    companion object {
        const val ACTION_PLAY_ID = "app.hush.music.lite.PLAY_ID"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        private const val ROOT = "root"
        private const val SEARCHED = "searched"
        private const val TAG = "HushLiteSession"

        fun item(videoId: String, title: String, artist: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(Uri.parse("hushlite://track/" + videoId))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setIsPlayable(true)
                        .build()
                ).build()
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        LiteSession.initialize()
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme != "hushlite") return@Factory spec
            val videoId = spec.uri.lastPathSegment.orEmpty()
            val url = runBlocking(Dispatchers.IO) {
                LiteStreamProbe.directAudioUrl(videoId)
            }
            spec.withUri(Uri.parse(url))
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            ).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Audio resolver failed; do not assume successful Hush playback", error)
            }
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LiteCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_ID) {
            val id = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
            if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                player.setMediaItem(item(
                    id,
                    intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                ))
                player.prepare()
                player.play()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class LiteCallback : MediaLibrarySession.Callback {
        private val byId = ConcurrentHashMap<String, MediaItem>()
        private val queries = ConcurrentHashMap<String, List<MediaItem>>()

        private fun toItem(candidate: HushLiteSongSearch.Candidate): MediaItem =
            item(candidate.videoId, candidate.title, candidate.artistNames.joinToString(", "))
                .also { byId[it.mediaId] = it }

        private suspend fun search(query: String): List<MediaItem> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return HushLiteSongSearch.songs(q, 50)
                .getOrElse {
                    Log.w(TAG, "Hush InnerTube search error", it)
                    emptyList()
                }.map(::toItem).also { queries[q] = it }
        }

        private fun rootItem(): MediaItem =
            MediaItem.Builder().setMediaId(ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle("YouTube Music")
                        .setIsBrowsable(true).setIsPlayable(false).build()
                ).build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future(Dispatchers.IO) {
                val items: List<MediaItem> = when (parentId) {
                    ROOT -> listOf(MediaItem.Builder().setMediaId(SEARCHED)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("검색한 음악")
                                .setIsBrowsable(true).setIsPlayable(false).build()
                        ).build())
                    SEARCHED -> queries.values.firstOrNull().orEmpty()
                    else -> emptyList()
                }
                val safePageSize = pageSize.coerceIn(1, 50)
                val from = (page.coerceAtLeast(0) * safePageSize).coerceAtMost(items.size)
                val to = (from + safePageSize).coerceAtMost(items.size)
                LibraryResult.ofItemList(items.subList(from, to), params)
            }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch(Dispatchers.IO) {
                val found = search(query)
                session.notifySearchResultChanged(browser, query, found.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future(Dispatchers.IO) {
                val all = queries[query.trim()] ?: search(query)
                val safePageSize = pageSize.coerceIn(1, 50)
                val from = (page.coerceAtLeast(0) * safePageSize).coerceAtMost(all.size)
                val to = (from + safePageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(all.subList(from, to), params)
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future(Dispatchers.IO) {
                val first = mediaItems.firstOrNull()
                    ?: return@future MediaSession.MediaItemsWithStartPosition(
                        emptyList(), 0, startPositionMs)
                // Same AA searchQuery entry point observed in Hush v13.14.7.
                val voice = first.requestMetadata.searchQuery?.trim().orEmpty()
                val chosen: List<MediaItem> = when {
                    voice.isNotEmpty() -> search(voice)
                    byId[first.mediaId] != null -> listOf(byId.getValue(first.mediaId))
                    first.mediaId.matches(Regex("[A-Za-z0-9_-]{11}")) ->
                        listOf(item(first.mediaId, first.mediaMetadata.title?.toString().orEmpty(),
                            first.mediaMetadata.artist?.toString().orEmpty()))
                    else -> emptyList()
                }
                MediaSession.MediaItemsWithStartPosition(chosen, 0, startPositionMs)
            }
    }
}
