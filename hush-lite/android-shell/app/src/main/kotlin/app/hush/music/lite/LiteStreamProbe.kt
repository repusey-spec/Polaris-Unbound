package app.hush.music.lite

import android.net.Uri
import android.os.SystemClock
import app.hush.music.innertube.YouTube
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-only wrapper around the preserved Hush InnerTube/NewPipe resolver.
 *
 * The short, account-scoped memory cache prevents a second player loader
 * request for the same stream after a seek/range reopen. No URL/cookie is
 * persisted to storage or recorded in logs.
 */
internal object LiteStreamProbe {
    private data class Cached(val url: String, val validUntilMs: Long)
    private val urls = ConcurrentHashMap<String, Cached>()
    private const val MAX_ENTRIES = 64
    private const val MAX_TTL_MS = 120_000L
    private const val SAFETY_MS = 60_000L

    suspend fun directAudioUrl(videoId: String): String {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid video ID" }
        val key = videoId + ":" + YouTube.currentPlaybackAuthState().fingerprint
        val now = SystemClock.elapsedRealtime()
        urls[key]?.let { if (now < it.validUntilMs) return it.url }

        // Calls Hush's unchanged NewPipeUtils.getStreamUrl for each candidate
        // instead of discarding ciphered or throttled formats.
        val url = HushLiteStreamResolver.audioUrl(videoId)
        val expireSeconds = runCatching {
            Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()
        }.getOrNull()
        val serverRemainingMs = expireSeconds?.let {
            it * 1000L - System.currentTimeMillis() - SAFETY_MS
        } ?: MAX_TTL_MS

        val ttlMs = minOf(MAX_TTL_MS, serverRemainingMs)
        if (ttlMs > 0L) {
            if (urls.size >= MAX_ENTRIES) urls.clear()
            urls[key] = Cached(url, SystemClock.elapsedRealtime() + ttlMs)
        }
        return url
    }

    /** Clear a rejected/expired stream on player error; retry is explicit. */
    fun invalidate(videoId: String) {
        val prefix = videoId + ":"
        urls.keys.removeAll { it.startsWith(prefix) }
    }
}
