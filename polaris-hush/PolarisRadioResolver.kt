package app.hush.music.polaris

import java.net.HttpURLConnection
import java.net.URL

/**
 * Port of PolarisMediaService.resolveStreamUrl/extractStreamUrl.
 * Call on IO dispatcher, never on main thread. Resolve on each station start,
 * not when building AA browse lists (URLs can expire).
 */
object PolarisRadioResolver {
    private val lookup = mapOf(
        "kr1" to "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25",
        "kr2" to "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm&callback=jarvis.miniInfo.loadOnAirComplete",
        "kr4" to "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=sfm&callback=jarvis.miniInfo.loadOnAirComplete",
        "kr6" to "https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y",
    )

    fun needsResolution(id: String): Boolean = lookup.containsKey(id)

    fun resolve(id: String): String {
        val endpoint = lookup[id] ?: error("No radio resolver for $id")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            return extractStreamUrl(raw)
                ?: error("No audio stream URL in $id resolver response")
        } finally {
            connection.disconnect()
        }
    }

    fun extractStreamUrl(raw: String): String? {
        val normalized = raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val candidates = Regex("""https?://[^"'\s,}\)]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized).map { it.value }.toList()
        return candidates.firstOrNull {
            it.contains(".m3u8") || it.contains(".aac") ||
                it.contains(".mp3") || it.contains("stream")
        } ?: candidates.firstOrNull()
    }
}
