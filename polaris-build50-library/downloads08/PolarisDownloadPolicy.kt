package app.hush.music.polaris

import java.io.IOException
import kotlinx.coroutines.*

class PolarisResolutionTimeout : IOException("주소 조회 제한 시간 초과 (30초)")
class PolarisVerificationTimeout : IOException("파일 검증 제한 시간 초과 · 임시 파일 보존")

object PolarisDownloadPolicy {
    const val RESOLVE_MS = 30_000L
    const val PROBE_MS = 6_000L
    const val PROBE_SAMPLES = 32
    fun reason(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        return when {
            causes.any { it is PolarisResolutionTimeout } -> "주소 조회 시간 초과"
            causes.any { it is PolarisVerificationTimeout } -> "검증 시간 초과 · 재시도 가능"
            causes.any { it.javaClass.simpleName.contains("LoginRequired") || it.javaClass.simpleName.contains("LoginContext") || it.javaClass.simpleName.contains("AddressChallenged") } -> "인증 필요 / 주소 요청 거부"
            causes.any { it.message.orEmpty().contains("ENOSPC") || it.message.orEmpty().contains("No space left", true) } -> "저장 공간 부족"
            causes.any { it is SecurityException } -> "저장소 권한 오류"
            causes.any { it is PolarisIncompleteDownload } -> "음원 용량 불일치"
            causes.any { it is java.net.SocketTimeoutException } -> "네트워크 읽기 시간 초과"
            else -> "다운로드/음원 검사 실패 · 오류 리포트 확인"
        }
    }
    fun mayRetry(error: Throwable): Boolean = error is IOException &&
        error !is PolarisResolutionTimeout && error !is PolarisVerificationTimeout &&
        reason(error) !in setOf("인증 필요 / 주소 요청 거부", "저장 공간 부족", "저장소 권한 오류")

    suspend fun <T> resolve(deadlineMs: Long, now: () -> Long, block: suspend () -> T): T {
        val remaining = deadlineMs - now()
        if (remaining <= 0) throw PolarisResolutionTimeout()
        try { return withTimeout(remaining) { block() } }
        catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw PolarisResolutionTimeout()
        }
    }
}

object PolarisPlaybackFailurePolicy {
    enum class Decision { SKIP, STOP, WAIT }
    fun recovered(expectedId: String, actualId: String?, wanted: Boolean, playing: Boolean, start: Long, position: Long): Boolean =
        expectedId == actualId && wanted && playing && position - start >= 1_000L
    fun decide(autoSkip: Boolean, connected: Boolean, wanted: Boolean, failures: Int, current: Int, next: Int): Decision = when {
        !wanted -> Decision.STOP
        !connected -> Decision.WAIT
        autoSkip && failures < 5 && next >= 0 && next != current -> Decision.SKIP
        else -> Decision.STOP
    }
}
