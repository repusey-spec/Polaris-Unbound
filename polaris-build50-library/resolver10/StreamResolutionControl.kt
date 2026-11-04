package app.hush.music.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolver Result boundaries must never turn cancellation into another client attempt. */
object StreamResolutionControl {
    suspend inline fun <T> attempt(block: () -> T): Result<T> {
        currentCoroutineContext().ensureActive()
        val result = try {
            Result.success(block())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    /** Only fixed categories leave memory; exception text may contain signed URLs. */
    fun category(error: Throwable): String {
        var cause: Throwable? = error
        repeat(12) {
            val current = cause ?: return "url-conversion"
            if (current is CancellationException) return "cancelled"
            val text = current.message.orEmpty().lowercase(java.util.Locale.ROOT)
            if ("deobfuscation" in text || "javascript player" in text || "signature" in text)
                return "signature-extractor"
            cause = current.cause
        }
        return "url-conversion"
    }

    fun candidate(videoId: String, client: String, itag: Int, outcome: String,
                  gvsReady: Boolean, error: Throwable? = null) {
        PlaybackTrace.emit("stream-candidate id=${PlaybackTrace.id(videoId)} client=${PlaybackTrace.label(client)} itag=$itag outcome=${PlaybackTrace.label(outcome)} gvsReady=$gvsReady error=${error?.let { PlaybackTrace.label(it.javaClass.simpleName) } ?: "none"} category=${error?.let(::category) ?: "none"}")
    }
}

/** A usable player response whose URLs failed locally must not become 'please log in'. */
class StreamCandidateException(val kind: String, cause: Throwable? = null) :
    IllegalStateException(when (kind) {
        "missing-gvs-token" -> "음원 전송 토큰 준비 실패"
        "decipher-cooldown", "signature-extractor" -> "음원 주소 서명 변환 실패"
        else -> "음원 주소 변환 실패"
    }, cause)
