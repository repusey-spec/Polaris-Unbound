package app.hush.music.polarisv5

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.polarisunbound.app.CurrentProgramResolver
import com.polarisunbound.app.RadioPresets
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polaris v5.0. Independent radio adapter for the unmodified Hush v13.14.8
 * streaming engine. Its station order, programme schedules, physical 4x4
 * layout and artwork are imported unchanged from the frozen Polaris v0.58.
 *
 * Radio items contain a *logical* URI. The Media3 resolving data-source
 * converts that URI to a fresh radio stream only when playback starts;
 * this leaves all 13 items in the actual Media3 timeline for AA's native
 * Queue button and steering-wheel previous/next without 13 network lookups.
 */
object V5Radio {
    const val ROOT = "polaris:v5:radio"
    const val STREAMING = "polaris:v5:streaming"
    const val STATION_PREFIX = "polaris:v5:station:"
    const val SCHEME = "polarisradio"

    private val stationStreams = mapOf(
        "kr1" to "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25",
        "kr2" to "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm&callback=jarvis.miniInfo.loadOnAirComplete",
        "kr3" to "http://m-aac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8",
        "kr4" to "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=sfm&callback=jarvis.miniInfo.loadOnAirComplete",
        "kr5" to "https://playerservices.streamtheworld.com/api/livestream-redirect/AFNP_DGU_SC",
        "kr6" to "https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y",
        "gallery" to "https://streaming.live365.com/a94394",
        "wild949" to "https://stream.revma.ihrhls.com/zc305",
        "z100" to "https://stream.revma.ihrhls.com/zc1469",
        "power1051" to "https://stream.revma.ihrhls.com/zc1481",
        "kiis" to "https://stream.revma.ihrhls.com/zc185",
        "alt987" to "https://stream.revma.ihrhls.com/zc201",
        "myfm1043" to "https://stream.revma.ihrhls.com/zc173",
    )
    private val dynamicIds = setOf("kr1", "kr2", "kr4", "kr6")
    private val hlsIds = setOf("kr1","kr2","kr3","kr4","kr6",
        "wild949","z100","power1051","kiis","alt987","myfm1043")
    private val started = AtomicBoolean(false)
    private val scheduler = Executors.newScheduledThreadPool(2)

    fun valid(id: String?) = id != null && stationStreams.containsKey(id)
    fun isRadio(mediaId: String?) = mediaId?.startsWith(STATION_PREFIX) == true
    fun idFromMedia(mediaId: String?) =
        mediaId?.takeIf { isRadio(it) }?.removePrefix(STATION_PREFIX)?.takeIf(::valid)

    fun ordered(context: Context): List<String> =
        RadioPresets.orderedIds(context).filter(::valid)

    fun artwork(context: Context, id: String): Uri =
        Uri.parse("content://" + context.packageName + ".v5art/" + id)

    fun displayTitle(context: Context, id: String): String {
        val cached = CurrentProgramResolver.cached(context, id)
        return if (CurrentProgramResolver.isCurrentNow(id, cached)) {
            cached.aaTitle(id).takeIf { it.isNotBlank() } ?: RadioPresets.stationName(id)
        } else {
            val name = RadioPresets.stationName(id)
            if (CurrentProgramResolver.hasLocalClock(id))
                name + "  |  " + CurrentProgramResolver.localClockLabel(id)
            else name
        }
    }

    fun item(context: Context, id: String): MediaItem {
        require(valid(id)) { "Unknown Polaris station: " + id }
        val metadata = MediaMetadata.Builder()
            .setTitle(displayTitle(context, id))
            .setArtist(RadioPresets.stationName(id))
            .setArtworkUri(artwork(context, id))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(STATION_PREFIX + id)
            .setUri(Uri.parse(SCHEME + "://station/" + id))
            .setMediaMetadata(metadata)
        if (id in hlsIds) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    fun queue(context: Context) = ordered(context).map { item(context, it) }
    fun browse(context: Context) = ordered(context).map { item(context, it) }

    /**
     * Called on Media3's data-source worker, NOT on the player main thread.
     * A live URI is freshly obtained on the first open, after every station
     * skip, and after resuming a saved queue (never a stale .m3u8 token).
     */
    @Throws(IOException::class)
    fun resolve(id: String): Uri {
        val lookup = stationStreams[id] ?: throw IOException("Unknown radio station " + id)
        val stream = when {
            id in dynamicIds -> resolveApi(id, lookup)
            id == "gallery" -> followLive365(lookup)
            else -> lookup
        }
        require(stream.startsWith("https://") || stream.startsWith("http://")) {
            "Invalid radio stream URI"
        }
        return Uri.parse(stream)
    }

    private fun resolveApi(id: String, lookup: String): String {
        val connection = (URL(lookup).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
                .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
            // Polaris v0.58's legacy resolver chooses an audio URL, not the
            // API URL. Prefer actual .m3u8, AAC or stream URLs in JSON/JSONP.
            val candidates = Regex("""https?://[^"'\s,}\)]+""")
                .findAll(body).map { it.value }.toList()
            val audio = candidates.firstOrNull {
                it.contains(".m3u8", true) || it.contains(".aac", true) ||
                    it.contains(".mp3", true) || it.contains("stream", true)
            } ?: candidates.firstOrNull()
            if (audio.isNullOrBlank()) throw IOException("Radio resolver returned no stream for " + id)
            Log.i("PolarisV5", "Resolved broadcaster=" + id)
            return audio
        } finally {
            connection.disconnect()
        }
    }

    private fun followLive365(lookup: String): String {
        var next = lookup
        repeat(6) {
            val connection = (URL(next).openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36")
                connection.setRequestProperty("Referer", "https://live365.com/")
                connection.setRequestProperty("Icy-MetaData", "1")
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    next = URL(URL(next), location).toString()
                } else if (code in 200..299) {
                    return next
                } else {
                    throw IOException("Live365 HTTP " + code)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Live365 excessive redirects")
    }

    /**
     * Programmes are resolved with the unmodified v0.58 Naver/Korean and
     * US local-time parsing logic. Timing is taken from its nextRefreshDelay.
     */
    fun beginProgrammeRefresh(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        ordered(app).filter { it != "kr5" }.forEachIndexed { index, id ->
            scheduleProgramme(app, id, index * 500L)
        }
    }

    private fun scheduleProgramme(context: Context, id: String, delayMs: Long) {
        scheduler.schedule({
            val delay = try {
                val result = CurrentProgramResolver.resolve(id)
                CurrentProgramResolver.save(context, id, result)
                CurrentProgramResolver.nextRefreshDelay(id, result).coerceAtLeast(60_000L)
            } catch (error: Exception) {
                Log.w("PolarisV5", "Programme refresh " + id, error)
                90_000L
            }
            scheduleProgramme(context, id, delay)
        }, delayMs, TimeUnit.MILLISECONDS)
    }
}
