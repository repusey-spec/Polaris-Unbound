package app.hush.music.polaris

import java.net.URI
import java.util.Locale

/** Pure policy, independently testable without Android or a player-global mode. */
object PolarisSourcePolicy {
    fun isRadio(mediaId: String): Boolean = mediaId.startsWith("polaris:station:")

    fun audioUrlScore(value: String, field: String = ""): Int {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return -1
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https") || uri.host.isNullOrBlank()) return -1
        val path = uri.path.orEmpty().lowercase(Locale.US)
        val key = field.lowercase(Locale.US)
        if (Regex("\\.(png|jpe?g|gif|webp|svg|html?|json|xml|js|css)$").containsMatchIn(path)) return -1
        if (listOf("image", "thumb", "logo", "homepage", "website", "poster").any { it in key }) return -1
        return when {
            path.endsWith(".m3u8") -> 100
            Regex("\\.(aac|mp3|m4a|ogg|opus)$").containsMatchIn(path) -> 90
            path.endsWith(".stream") -> 85
            // An explicitly named audio field supports extensionless broadcaster URLs.
            listOf("aac", "audio", "hls", "streamurl", "stream_url", "liveurl", "live_url").any { it in key } -> 75
            else -> -1
        }
    }

    fun redact(text: String): String {
        var safe = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).replace(text) { match ->
            val uri = runCatching { URI(match.value) }.getOrNull()
            if (uri?.host != null) "${uri.scheme}://${uri.host}/[redacted]" else "[url redacted]"
        }
        safe = Regex("(?i)(authorization|cookie|set-cookie)[ \\t]*[:=][^\\r\\n]*")
            .replace(safe) { "${it.groupValues[1]}=[redacted]" }
        safe = Regex("(?i)(access_token|refresh_token|visitor_data|po_token|token|signature|password)([\\s:=]+)([^\\s,;]+)")
            .replace(safe) { "${it.groupValues[1]}=[redacted]" }
        return safe
    }
}
