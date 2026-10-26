package app.hush.music.polaris

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Polaris v0.58 radio retry rules adapted to Hush's SINGLE Media3 player.
 *
 * Unlike MusicService's normal YouTube error recovery, a radio error requires
 * re-resolving an expiring broadcaster URL and starting at the current LIVE
 * position. Do not use the old HLS timeline or apply radio logic to songs.
 *
 * Re-resolve on terminal error, not periodically while a stream is playing.
 * Preserve the player and session; never launch a second music service.
 */
class PolarisRadioRecovery(private val player: Player) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stationId: String? = null
    private var epoch = 0L
    private var attempts = 0
    private var retryJob: Job? = null
    private var stablePlaybackJob: Job? = null

    private fun activeId(): String? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        if (!mediaId.startsWith("polaris:station:")) return null
        return mediaId.removePrefix("polaris:station:")
            .takeIf { RadioCatalog.find(it) != null }
    }

    fun onItemTransition(item: MediaItem?) {
        val newId = item?.mediaId?.takeIf { it.startsWith("polaris:station:") }
            ?.removePrefix("polaris:station:")
            ?.takeIf { RadioCatalog.find(it) != null }
        if (newId == stationId) return
        stationId = newId
        epoch++
        attempts = 0
        retryJob?.cancel()
        stablePlaybackJob?.cancel()
    }

    fun onPlayWhenReadyChanged(ready: Boolean) {
        if (ready) return
        // A manual pause, loss of focus, or switching to Streaming must not
        // resurrect a radio station by a scheduled network callback.
        epoch++
        retryJob?.cancel()
        stablePlaybackJob?.cancel()
    }

    fun onIsPlayingChanged(playing: Boolean) {
        stablePlaybackJob?.cancel()
        if (!playing || activeId() == null) return
        retryJob?.cancel()
        val capturedEpoch = epoch
        stablePlaybackJob = scope.launch {
            delay(15_000)
            if (epoch == capturedEpoch && player.isPlaying && activeId() == stationId) {
                attempts = 0
            }
        }
    }

    fun onPlaybackStateChanged(state: Int) {
        if (activeId() == null || !player.playWhenReady) return
        // v0.58 does NOT replace a live HLS MediaSource just for BUFFERING.
        // The RadioTalk load-error policy retries segment/playlist loads
        // inside that MediaSource; only a terminal error refreshes the URL.
        if (state == Player.STATE_ENDED) {
            scheduleRetry(100L)
        }
    }

    /** Returns true only for a radio error that must bypass Hush's song recovery. */
    fun onPlayerError(): Boolean {
        val id = activeId() ?: return false
        if (player.playWhenReady) {
            Log.w("PolarisRadio", "Terminal player error, refreshing source: $id")
            scheduleRetry(if (PolarisRadioResolver.needsResolution(id)) 100L else 750L)
        }
        return true
    }

    private fun scheduleRetry(initialDelayMs: Long) {
        val id = activeId() ?: return
        if (!player.playWhenReady || retryJob?.isActive == true) return
        attempts++
        val delayMs = if (attempts == 1) initialDelayMs else
            when (attempts) {
                2 -> 2_000L
                3 -> 5_000L
                4 -> 10_000L
                else -> 30_000L
            }
        val capturedEpoch = epoch
        retryJob = scope.launch {
            delay(delayMs)
            if (epoch != capturedEpoch || activeId() != id || !player.playWhenReady) return@launch
            try {
                val freshUrl = withContext(Dispatchers.IO) {
                    PolarisRadioPlayback.resolveUrl(id)
                }
                if (epoch != capturedEpoch || activeId() != id || !player.playWhenReady) return@launch
                val freshItem = PolarisRadioPlayback.mediaItem(id, freshUrl)
                Log.i("PolarisRadio", "Radio source refreshed: $id attempt=$attempts")
                player.setMediaItem(freshItem)
                player.prepare()
                player.play()
            } catch (e: Exception) {
                Log.w("PolarisRadio", "Radio URL refresh failed: $id", e)
                if (epoch == capturedEpoch && activeId() == id && player.playWhenReady) {
                    // Mark the currently executing job complete before rescheduling.
                    retryJob = null
                    scheduleRetry(2_000L)
                }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
