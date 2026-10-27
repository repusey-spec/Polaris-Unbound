package app.hush.music.polaris

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/**
 * The Polaris station id is stable; network stream URLs are per-play tokens.
 * Used equally by phone, AA, and service recovery.
 */
object PolarisRadioPlayback {
    fun resolveUrl(id: String): String =
        if (PolarisRadioResolver.needsResolution(id)) {
            PolarisRadioResolver.resolve(id)
        } else {
            PolarisRadioStreams.streams.firstOrNull { it.id == id }?.url
                ?: error("Missing stream for radio id $id")
        }

    fun mediaItem(id: String, url: String): MediaItem {
        require(RadioCatalog.find(id) != null) { "Unknown radio station $id" }
        val builder = MediaItem.Builder()
            .setMediaId("polaris:station:$id")
            .setUri(url)
            .setMediaMetadata(PolarisRadioMetadata.mediaMetadata(id))
        // Polaris v0.58: explicitly identify HLS to ExoPlayer and target 20s
        // from live edge. Some broadcasters return an extensionless HLS URL.
        val lower = url.lowercase(java.util.Locale.US)
        if (lower.contains(".m3u8") || lower.contains("format=m3u8") || lower.contains("protocol=hls")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(20_000L)
                        .build()
                )
        }
        return builder.build()
    }
}
