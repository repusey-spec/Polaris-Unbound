package app.hush.music.lite

import android.net.Uri
import android.util.Log
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.YouTubeClient
import kotlinx.coroutines.withTimeout

/**
 * First isolated streaming *probe*, NOT the complete Hush MusicService resolver.
 * The upstream engine has cipher/PoToken/client fallback paths not yet ported;
 * never silently substitute another song when direct URL resolution fails.
 */
internal object LiteStreamProbe {
    suspend fun directAudioUrl(videoId: String): String {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid YouTube video ID" }
        val clients = listOf(
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.IOS,
            YouTubeClient.ANDROID_MUSIC,
        )
        val failures = mutableListOf<String>()
        return withTimeout(15000L) {
            for (client in clients) {
                val attempt = YouTube.player(
                    videoId = videoId,
                    client = client,
                    setLogin = client.loginSupported,
                )
                if (attempt.isFailure) {
                    failures += client.clientName + ": " +
                        attempt.exceptionOrNull()?.javaClass?.simpleName
                    continue
                }
                val response = attempt.getOrThrow()
                val url = response.streamingData
                    ?.adaptiveFormats
                    ?.asSequence()
                    ?.filter { it.isAudio && it.url?.startsWith("https://") == true }
                    ?.sortedByDescending { it.bitrate }
                    ?.firstOrNull()
                    ?.url
                if (url != null) return@withTimeout url
                failures += client.clientName + ": no directly playable audio URL"
            }
            Log.w("HushLiteStream", "No direct audio URL for video ID; upstream stream resolver still required")
            throw IllegalStateException("Stream unavailable: " + failures.joinToString("; "))
        }
    }
}
