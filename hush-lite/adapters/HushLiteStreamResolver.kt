/*
 * Polaris Hush Lite integration layer.
 * Uses unchanged Hush/ArchiveTune v13.14.7 NewPipeUtils and InnerTube sources (GPL-3.0).
 * This intentionally does not replicate the huge Hush MusicService.
 */
package app.hush.music.lite

import app.hush.music.innertube.NewPipeUtils
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.YouTubeClient
import kotlinx.coroutines.withTimeout

/**
 * Converts one known YouTube Music ID into a playable audio URL.
 *
 * The original Hush core provides NewPipe URL resolution including signature
 * and throttling-parameter handling. Requests may still fail for unavailable
 * streams, missing account state or extra web proof-of-origin requirements:
 * this is NOT a complete replacement for Hush YTPlayerUtils + BotGuard.
 *
 * No song substitution, no second title search, no sensitive URLs logged.
 */
object HushLiteStreamResolver {
    private val videoIdRegex = Regex("[A-Za-z0-9_-]{11}")

    /**
     * A client list is kept explicit so the first result cannot silently
     * escape to an unrelated web/app intent or a different video.
     */
    private val clients: List<YouTubeClient> = listOf(
        YouTubeClient.ANDROID_VR_1_65_10,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.IOS,
        YouTubeClient.WEB_REMIX,
    )

    suspend fun audioUrl(videoId: String): String {
        require(videoIdRegex.matches(videoId)) { "Invalid 11-character videoId" }
        return withTimeout(25000L) {
            val errors = mutableListOf<String>()
            var signatureTimestamp: Int? = null
            var timestampAttempted = false

            for (client in clients) {
                if (client.useSignatureTimestamp && !timestampAttempted) {
                    timestampAttempted = true
                    signatureTimestamp = NewPipeUtils.getSignatureTimestamp(videoId).getOrNull()
                }

                val auth = YouTube.currentPlaybackAuthState()
                val responseResult = YouTube.player(
                    videoId = videoId,
                    client = client,
                    signatureTimestamp = if (client.useSignatureTimestamp) signatureTimestamp else null,
                    setLogin = client.loginSupported && auth.hasPlaybackLoginContext,
                    authState = auth,
                )
                if (responseResult.isFailure) {
                    errors.add(client.clientName + ":player-request")
                    continue
                }
                val response = responseResult.getOrThrow()
                if (response.playabilityStatus.status != "OK") {
                    errors.add(client.clientName + ":not-playable")
                    continue
                }

                val candidates = response.streamingData
                    ?.adaptiveFormats
                    .orEmpty()
                    .asSequence()
                    .filter {
                        it.isAudio &&
                            it.bitrate > 0 &&
                            (!it.url.isNullOrBlank() ||
                                !it.signatureCipher.isNullOrBlank() ||
                                !it.cipher.isNullOrBlank())
                    }
                    .sortedWith(
                        compareByDescending<app.hush.music.innertube.models.response.PlayerResponse.StreamingData.Format> {
                            !it.url.isNullOrBlank()
                        }.thenByDescending { it.bitrate }
                    )
                    .take(5)
                    .toList()

                for (format in candidates) {
                    val url = NewPipeUtils.getStreamUrl(
                        format = format,
                        videoId = videoId,
                        client = client,
                        authState = auth,
                    ).getOrNull()
                    if (url != null && url.startsWith("https://")) {
                        return@withTimeout url
                    }
                }
                errors.add(client.clientName + ":no-resolved-audio")
            }

            // Do not include URLs/cookies/tokens in exception text or logs.
            throw IllegalStateException(
                "Hush stream resolution unavailable for selected ID (" +
                    errors.joinToString(",") + ")"
            )
        }
    }
}
