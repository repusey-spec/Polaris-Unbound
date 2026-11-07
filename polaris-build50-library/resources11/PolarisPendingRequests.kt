package app.hush.music.polaris

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Request identity is independent of video/session identity; cancellation never leaves a waiter. */
class PolarisPendingRequests<T> {
    private val serial = AtomicLong()
    private val waiters = ConcurrentHashMap<String, CancellableContinuation<T>>()
    val size: Int get() = waiters.size
    fun add(waiter: CancellableContinuation<T>): String {
        val id = serial.incrementAndGet().toString()
        waiters[id] = waiter
        waiter.invokeOnCancellation { waiters.remove(id, waiter) }
        return id
    }
    fun complete(id: String, value: T) { waiters.remove(id)?.let { if (it.isActive) it.resume(value) } }
    fun fail(id: String, failure: Throwable) { waiters.remove(id)?.let { if (it.isActive) it.resumeWithException(failure) } }
    fun cancelAll() {
        val pending = waiters.values.toList()
        waiters.clear()
        pending.forEach { it.cancel(CancellationException("engine closed")) }
    }
}
