package app.hush.music.polaris

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build 49: Keep Android Auto's built-in "Queue" populated with the user's
 * 13 radio presets while preserving the current live player's existing source.
 *
 * The original station source remains untouched when the timeline is expanded.
 * Only stations whose URL is API-generated are represented by a non-playable
 * placeholder until the user selects them in the AA queue. Selection triggers
 * a fresh URL resolution before preparing playback.
 */
class PolarisRadioQueue(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private var serial = 0L
    private var resolveJob: Job? = null
    private val placeholderAuthority = "polaris.invalid"

    private fun radioId(item: MediaItem?): String? =
        item?.mediaId?.takeIf { it.startsWith("polaris:station:") }
            ?.removePrefix("polaris:station:")
            ?.takeIf { RadioCatalog.find(it) != null }

    private fun queueItem(id: String): MediaItem {
        val direct = PolarisRadioStreams.streams.firstOrNull { it.id == id }?.url
        val url = direct ?: "https://" + placeholderAuthority + "/station/" + id
        return PolarisRadioPlayback.mediaItem(id, url)
    }

    fun onTransition(item: MediaItem?) {
        ++serial
        resolveJob?.cancel()
        val id = radioId(item) ?: return
        val order = PolarisRadioLayout(context).slots().filterNotNull()
        if (player.mediaItemCount == 1 && order.size > 1) {
            val selected = order.indexOf(id)
            if (selected >= 0) {
                val before = order.take(selected).map(::queueItem)
                val after = order.drop(selected + 1).map(::queueItem)
                // Adding around the active item keeps its MediaSource and playback position.
                if (before.isNotEmpty()) player.addMediaItems(0, before)
                if (after.isNotEmpty()) player.addMediaItems(player.mediaItemCount, after)
                PolarisRadioDiagnostics.record(context, "AA radio queue populated stations=" + order.size)
            }
        }

        val uri: Uri = item?.localConfiguration?.uri ?: return
        if (uri.host != placeholderAuthority) return

        val thisSerial = serial
        val index = player.currentMediaItemIndex
        // Prevent the placeholder from issuing an HTTP request while the station
        // resolver obtains a fresh broadcaster URL on the IO dispatcher.
        player.pause()
        player.stop()
        resolveJob = scope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { PolarisRadioPlayback.resolveUrl(id) }
                if (serial != thisSerial || player.currentMediaItemIndex != index ||
                    radioId(player.currentMediaItem) != id) return@launch
                player.replaceMediaItem(index, PolarisRadioPlayback.mediaItem(id, resolved))
                player.prepare()
                player.play()
                PolarisRadioDiagnostics.record(context, "AA queue selected live station=" + id)
            } catch (error: Exception) {
                if (serial == thisSerial) {
                    PolarisRadioDiagnostics.record(
                        context, "AA queue station resolution failed id=" + id +
                            " error=" + error.javaClass.simpleName
                    )
                }
            }
        }
    }
}
