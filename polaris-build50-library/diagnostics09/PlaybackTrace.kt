package app.hush.music.innertube

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Structured allowlist only: no credential values, URLs, titles or arbitrary server text. */
object PlaybackTrace {
    @Volatile var sink: ((String) -> Unit)? = null
    private val counter = AtomicLong()
    private val boot = java.util.UUID.randomUUID().toString().take(8)
    private var lastAuth: String? = null
    private var epoch = 0L
    @Synchronized fun authEpoch(fingerprint: String): Long {
        if (lastAuth != fingerprint) { lastAuth = fingerprint; epoch++ }
        return epoch
    }
    fun nextId(): String = "$boot-${counter.incrementAndGet()}"
    fun id(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: "unknown"
    fun label(value: String): String = value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "other"
    fun emit(line: String) { try { sink?.invoke(line) } catch (_: Exception) {} }
    fun elapsed(start: Long): Long = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
    class Resolution(val attempt: String, val originalId: String, val purpose: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Resolution>
    }
    class Call(val request: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Call>
    }
    suspend fun <T> resolution(videoId: String, purpose: String, block: suspend () -> T): T {
        if (coroutineContext[Resolution] != null) return block()
        val context = Resolution(nextId(), id(videoId), label(purpose))
        val start = System.nanoTime()
        emit("resolution-start attempt=${context.attempt} original=${context.originalId} purpose=${context.purpose}")
        return try {
            withContext(context) { block() }.also {
                emit("resolution-end attempt=${context.attempt} outcome=success elapsedMs=${elapsed(start)}")
            }
        } catch (error: Throwable) {
            emit("resolution-end attempt=${context.attempt} outcome=${if (error is CancellationException) "cancelled-or-deadline" else "failure"} error=${label(error.javaClass.simpleName)} elapsedMs=${elapsed(start)}")
            throw error
        }
    }
    suspend fun stage(name: String, videoId: String) {
        val root = coroutineContext[Resolution]
        emit("resolution-stage attempt=${root?.attempt ?: "unscoped"} id=${id(videoId)} stage=${label(name)}")
    }
    fun reason(status: String, reason: String?): String {
        val text = reason.orEmpty().lowercase(java.util.Locale.ROOT)
        return when {
            "bot" in text || "로봇" in text || "자동화" in text -> "bot-confirmation"
            "age" in text || "연령" in text || "나이" in text -> "age-confirmation"
            "country" in text || "region" in text || "국가" in text || "지역" in text -> "region-restriction"
            "private" in text || "비공개" in text -> "private"
            "removed" in text || "삭제" in text -> "removed"
            status == "LOGIN_REQUIRED" || "sign in" in text || "log in" in text || "로그인" in text -> "login-required"
            status == "OK" -> "ok"
            reason.isNullOrBlank() -> "none"
            else -> "other-server-reason"
        }
    }
    fun status(value: String): String = value.takeIf { it in setOf("OK", "LOGIN_REQUIRED", "UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE", "CONTENT_CHECK_REQUIRED", "AGE_CHECK_REQUIRED") } ?: "OTHER"
    suspend fun <T> player(
        videoId: String, client: String, loginSupported: Boolean, setLogin: Boolean,
        auth: PlaybackAuthState, globalAuth: PlaybackAuthState, poToken: Boolean,
        block: suspend (String) -> T,
    ): T {
        val request = nextId()
        val root = coroutineContext[Resolution]
        val epochNow = authEpoch(globalAuth.fingerprint)
        val start = System.nanoTime()
        emit("player-start request=$request attempt=${root?.attempt ?: "unscoped"} purpose=${root?.purpose ?: "other"} original=${root?.originalId ?: id(videoId)} requested=${id(videoId)} client=${label(client)} loginSupported=$loginSupported setLogin=$setLogin cookiePresent=${!auth.cookie.isNullOrBlank()} loginCookie=${auth.hasLoginCookie} dataSync=${!auth.dataSyncId.isNullOrBlank()} visitor=${!auth.visitorData.isNullOrBlank()} playbackContext=${auth.hasPlaybackLoginContext} poToken=$poToken authEpoch=$epochNow authMatchesGlobal=${auth.fingerprint == globalAuth.fingerprint}")
        return try {
            withContext(Call(request)) { block(request) }.also {
                emit("player-end request=$request outcome=response elapsedMs=${elapsed(start)}")
            }
        } catch (error: Throwable) {
            emit("player-end request=$request outcome=${if (error is CancellationException) "cancelled-or-deadline" else "failure"} error=${label(error.javaClass.simpleName)} elapsedMs=${elapsed(start)}")
            throw error
        }
    }
    fun response(request: String, requested: String, returned: String?, status: String, reason: String?, audio: Int, direct: Int, cipher: Int) {
        emit("player-response request=$request requested=${id(requested)} returned=${id(returned)} idMatch=${if (returned == null) "unknown" else (requested == returned).toString()} status=${PlaybackTrace.status(status)} reason=${PlaybackTrace.reason(status, reason)} audio=$audio direct=$direct cipher=$cipher")
    }
    fun browse(client: String, auth: PlaybackAuthState, setLogin: Boolean, cookieHeader: Boolean, authorizationHeader: Boolean, dataSync: Boolean) {
        emit("browse-auth client=${label(client)} authEpoch=${authEpoch(auth.fingerprint)} setLogin=$setLogin cookiePresent=${!auth.cookie.isNullOrBlank()} loginCookie=${auth.hasLoginCookie} playbackContext=${auth.hasPlaybackLoginContext} cookieHeader=$cookieHeader authorizationHeader=$authorizationHeader dataSync=$dataSync")
    }
}
