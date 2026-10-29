package app.hush.music.polaris

import java.util.concurrent.atomic.AtomicLong

/**
 * Reject stale asynchronous UI, voice and AA source-switch requests.
 * A newly tapped station/track must win over earlier slow network responses.
 * No player operations are performed by this gate.
 */
object PolarisPlaybackRequestGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()
    fun cancel(): Long = generation.incrementAndGet()
    fun isCurrent(request: Long): Boolean = generation.get() == request
}
