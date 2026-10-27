package app.hush.music.polaris

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Browser/search metadata only. Playback URLs are resolved on selection. */
object PolarisVoiceSearchItem {
    fun mediaItem(id: String): MediaItem {
        require(RadioCatalog.find(id) != null) { "Unknown Polaris station" }
        return MediaItem.Builder()
            .setMediaId("polaris:station:" + id)
            .setMediaMetadata(
                PolarisRadioMetadata.mediaMetadata(id).buildUpon()
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()
    }
}
