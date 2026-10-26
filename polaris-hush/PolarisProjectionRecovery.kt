package app.hush.music.polaris

import android.content.Context
import android.os.SystemClock
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.58's projection-disconnect radio contract, isolated from Hush Streaming.
 * Clear the old player buffer on genuine AA projection loss, then reopen the
 * current station at the live edge using a newly resolved stream URL.
 *
 * Legacy MediaSession onUnbind/onDisconnected can fire on inactivity without
 * AA disconnecting; CarConnection is required to avoid false resets.
 */
class PolarisProjectionRecovery(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("polaris_radio_aa_resume", Context.MODE_PRIVATE)
    private var connection: CarConnection? = null
    private var observer: Observer<Int>? = null
    private var projected = false
    private var sawProjection = false
    private var generation = 0L
    private var restartJob: Job? = null
    private var lastAudibleAt = 0L
    private var lastAudibleId: String? = null

    fun start() {
        try {
            val car = CarConnection(context)
            val watch = Observer<Int> { type ->
                onProjectionState(type ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            }
            connection = car
            observer = watch
            car.type.observeForever(watch)
            log("projection observer registered")
        } catch (e: Throwable) {
            log("projection observer unavailable " + e.javaClass.simpleName)
        }
    }

    private fun stationId(): String? =
        player.currentMediaItem?.mediaId
            ?.takeIf { it.startsWith("polaris:station:") }
            ?.removePrefix("polaris:station:")
            ?.takeIf { RadioCatalog.find(it) != null }

    private fun pendingId(): String? =
        prefs.getString("station", null)?.takeIf { RadioCatalog.find(it) != null }

    private fun clearPending() {
        prefs.edit().remove("station").remove("resume").apply()
    }

    private fun log(message: String) = PolarisRadioDiagnostics.record(context, message)

    private fun onProjectionState(type: Int) {
        val active = type == CarConnection.CONNECTION_TYPE_PROJECTION
        log("AA connection state=" + type + " radio=" + (stationId() ?: "none"))
        if (active) {
            if (projected) return
            projected = true
            sawProjection = true
            val pending = pendingId()
            if (pending != null && prefs.getBoolean("resume", false)) {
                log("AA reconnected; queue fresh radio " + pending)
                scheduleFreshRadio(pending)
            }
            return
        }
        if (!projected || !sawProjection) return
        projected = false
        ++generation
        restartJob?.cancel()
        restartJob = null

        val current = stationId()
        if (current == null) {
            log("AA disconnected (no radio; Streaming untouched)")
            return
        }
        // Audio focus may be lost before CarConnection reports NOT_CONNECTED.
        val playedRecently =
            current == lastAudibleId && SystemClock.elapsedRealtime() - lastAudibleAt < 8_000L
        val resume = player.playWhenReady || playedRecently
        if (resume) {
            prefs.edit().putString("station", current).putBoolean("resume", true).apply()
        } else {
            clearPending()
        }
        log("AA disconnected radio=" + current + " resume=" + resume + " buffers=discard")
        player.pause()
        player.stop()
        player.clearMediaItems()
    }

    private fun scheduleFreshRadio(id: String) {
        val captureGeneration = ++generation
        restartJob?.cancel()
        restartJob = scope.launch {
            // As in v0.58, allow projection and output routing to settle first.
            delay(2_500L)
            if (!projected || generation != captureGeneration) return@launch
            val existingId = player.currentMediaItem?.mediaId
            if (existingId != null && existingId != "polaris:station:" + id) {
                // A user picked another station or Streaming before the restore.
                log("AA resume cancelled: user-selected media")
                clearPending()
                return@launch
            }
            try {
                val liveUrl = withContext(Dispatchers.IO) { PolarisRadioPlayback.resolveUrl(id) }
                if (!projected || generation != captureGeneration ||
                    (player.currentMediaItem != null && stationId() != id)
                ) return@launch
                PolarisRadioNetwork.radioActive = true
                player.setMediaItem(PolarisRadioPlayback.mediaItem(id, liveUrl))
                player.prepare()
                player.play()
                log("AA radio live source opened station=" + id)
                clearPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("AA fresh radio failed " + e.javaClass.simpleName)
                // Pending station remains available on the next AA reconnect.
            }
        }
    }

    fun onRadioPlaying(playing: Boolean) {
        if (playing) {
            stationId()?.let {
                lastAudibleAt = SystemClock.elapsedRealtime()
                lastAudibleId = it
            }
        }
    }

    fun onMediaItemTransition(item: MediaItem?) {
        val pending = pendingId() ?: return
        if (item == null) return // Expected when discarding buffers.
        if (item.mediaId != "polaris:station:" + pending) {
            ++generation
            restartJob?.cancel()
            clearPending()
            log("AA pending resume cancelled on media change")
        }
    }

    fun onPlayerError(code: Int) {
        if (stationId() != null) log("radio player error code=" + code)
    }

    fun onPlayerState(state: Int) {
        if (stationId() == null) return
        val label = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }
        log("radio state=" + label + " projected=" + projected)
    }

    fun onAudioRouteChanged() {
        if (stationId() != null) log("radio audio output changed projected=" + projected)
    }

    fun dispose() {
        ++generation
        restartJob?.cancel()
        observer?.let { watch -> connection?.type?.removeObserver(watch) }
        observer = null
        connection = null
        log("Polaris MusicService destroyed")
    }
}
