package app.hush.music.polaris

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MediaMetadata
import java.io.ByteArrayOutputStream

/** 0.58 AA station metadata: programme, local city clock and original station artwork. */
object PolarisRadioMetadata {
    @Volatile private var appContext: Context? = null
    private val artwork = mutableMapOf<String, ByteArray>()
    fun install(context: Context) { appContext = context.applicationContext }

    @Synchronized
    private fun artworkData(id: String): ByteArray? {
        val context = appContext ?: return null
        return artwork.getOrPut(id) {
            ByteArrayOutputStream().use { out ->
                StationArt.bitmap(context, id, 256).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
    }

    fun title(id: String): String {
        val station = RadioCatalog.find(id)?.title ?: CurrentProgramResolver.stationName(id)
        val context = appContext
        val cached = if (context == null) null else CurrentProgramResolver.cached(context, id)
        if (cached != null && CurrentProgramResolver.isCurrentNow(id, cached)) return cached.aaTitle(id)
        return if (CurrentProgramResolver.hasLocalClock(id)) {
            station + "  |  " + CurrentProgramResolver.localClockLabel(id)
        } else station
    }

    fun mediaMetadata(id: String): MediaMetadata {
        val station = RadioCatalog.find(id)?.title ?: CurrentProgramResolver.stationName(id)
        val builder = MediaMetadata.Builder()
            .setTitle(title(id))
            .setArtist(station)
            .setIsPlayable(true)
        artworkData(id)?.let { builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return builder.build()
    }
}
