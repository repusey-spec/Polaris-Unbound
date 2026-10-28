package app.hush.music.polaris

import android.content.Context
import androidx.media3.common.MediaMetadata

/** 0.58 AA station metadata: programme, local city clock and original station artwork. */
object PolarisRadioMetadata {
    @Volatile private var appContext: Context? = null
    fun install(context: Context) { appContext = context.applicationContext }

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
        builder.setArtworkUri(PolarisRadioArtwork.uri(id))
        return builder.build()
    }
}
