package app.hush.music.polaris

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Build 53: construct a complete radio queue BEFORE issuing a Media3 setMediaItems command.
 * No placeholder URI, no mutation from onMediaItemTransition and no player.stop().
 *
 * Dynamic broadcaster endpoints are resolved only for a queue about to play. If
 * an endpoint is unavailable the invalid item is excluded rather than risking
 * a Media3 load error. Other stations are preserved and order follows 16-slot UI.
 */
object PolarisRadioQueueBuilder {
    data class Ready(val items: List<MediaItem>, val index: Int)

    suspend fun build(context: Context, selectedId: String): Ready {
        require(RadioCatalog.find(selectedId) != null) { "Invalid radio station" }
        val ordered = PolarisRadioLayout(context).slots()
            .filterNotNull()
            .distinct()
            .filter { RadioCatalog.find(it) != null }
        val selectedUrl = withContext(Dispatchers.IO) {
            PolarisRadioPlayback.resolveUrl(selectedId)
        }
        val stationItems = coroutineScope {
            ordered.map { id ->
                async(Dispatchers.IO) {
                    val url = if (id == selectedId) {
                        selectedUrl
                    } else {
                        runCatching { PolarisRadioPlayback.resolveUrl(id) }.getOrNull()
                    }
                    id to url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        ?.let { PolarisRadioPlayback.mediaItem(id, it) }
                }
            }.awaitAll()
        }
        val items = stationItems.mapNotNull { it.second }
        val index = items.indexOfFirst { it.mediaId == "polaris:station:$"+"selectedId" }
        check(index >= 0) { "Selected radio station has no playable stream" }
        if (items.size != ordered.size) {
            PolarisRadioDiagnostics.record(
                context, "AA queue: playable="+items.size+" configured="+ordered.size
            )
        }
        return Ready(items, index)
    }
}
