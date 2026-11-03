package app.hush.music.polaris

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Internal radio requests are consumed here, never sent to an HTTP data source. */
object PolarisRadioRequests {
    const val SCHEME = "polaris-radio"

    fun stationId(item: MediaItem): String? = item.mediaId
        .takeIf { it.startsWith("polaris:station:") }
        ?.removePrefix("polaris:station:")
        ?.takeIf { RadioCatalog.find(it) != null }

    fun queueItem(id: String): MediaItem {
        require(RadioCatalog.find(id) != null) { "Unknown radio station" }
        if (!PolarisRadioResolver.needsResolution(id)) {
            return PolarisRadioPlayback.mediaItem(id, PolarisRadioPlayback.resolveUrl(id))
        }
        // All four token-based station endpoints return HLS. Resolution happens on
        // ExoPlayer's loader, before opening HTTP, including queue skips and restore.
        return PolarisRadioPlayback.mediaItem(id, "$SCHEME://station/$id")
            .buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
    }

    fun normalize(item: MediaItem): MediaItem {
        val id = stationId(item) ?: throw IllegalArgumentException("Unknown radio station")
        val uri = item.localConfiguration?.uri
        val validNetwork = uri?.scheme in listOf("http", "https") && !uri?.host.isNullOrBlank()
        val validInternal = uri?.scheme == SCHEME && uri.host == "station" && uri.path == "/$id"
        return if (validNetwork || validInternal) {
            item.buildUpon().setCustomCacheKey(null).build()
        } else {
            // Includes the old persisted setUri(mediaId) with scheme "polaris".
            queueItem(id).buildUpon().setTag(item.localConfiguration?.tag).build()
        }
    }

    fun resolve(spec: DataSpec, resolver: (String) -> String = PolarisRadioPlayback::resolveUrl): DataSpec {
        val uri = spec.uri
        val resolved = when (uri.scheme) {
            "http", "https" -> uri.toString()
            SCHEME -> {
                val id = uri.lastPathSegment.orEmpty()
                if (uri.host != "station" || uri.path != "/$id" || RadioCatalog.find(id) == null) {
                    throw IOException("Invalid internal radio request")
                }
                try { resolver(id) } catch (error: Exception) {
                    throw IOException("Radio address resolution failed station=$id", error)
                }
            }
            else -> throw IOException("Rejected radio URI scheme=${uri.scheme}")
        }
        val actual = android.net.Uri.parse(resolved)
        if (actual.scheme !in listOf("http", "https") || actual.host.isNullOrBlank()) {
            throw IOException("Radio resolver did not return an HTTP(S) address")
        }
        return spec.buildUpon().setUri(actual).setKey(null).build()
    }
}

/** Build the complete queue before assigning it; never mutate from transition callbacks. */
object PolarisRadioPlaylist {
    data class Selection(val items: List<MediaItem>, val index: Int)

    fun selection(context: Context, selected: MediaItem): Selection {
        val id = requireNotNull(PolarisRadioRequests.stationId(selected))
        val order = PolarisRadioLayout(context).slots().filterNotNull()
        val items = order.map { station ->
            if (station == id) PolarisRadioRequests.normalize(selected)
            else PolarisRadioRequests.queueItem(station)
        }
        return Selection(items, order.indexOf(id).also { require(it >= 0) })
    }

    fun set(player: Player, context: Context, selected: MediaItem) {
        val queue = selection(context, selected)
        player.setMediaItems(queue.items, queue.index, 0L)
        PolarisErrorReport.record("radio-queue count=${queue.items.size} selected=${queue.index}")
    }
}
