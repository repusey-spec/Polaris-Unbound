package app.hush.music.polaris

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Browser/search metadata only. Playback URLs are resolved on selection. */
object PolarisVoiceSearchItem {
    fun mediaItem(id: String): MediaItem {
        val station = RadioCatalog.find(id) ?: error("Unknown Polaris station")
        return MediaItem.Builder()
            .setMediaId("polaris:station:" + id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.title)
                    .setArtist("Polaris Radio")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()
    }
}
