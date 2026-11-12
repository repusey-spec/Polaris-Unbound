package app.hush.music.polaris

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Runs disk evidence checks in the caller's existing bounded prefetch job. */
internal object PolarisLocalPrefetchGate {
    suspend fun <T : Any> lookup(
        cached: () -> T?,
        needsFetch: () -> Boolean,
        resolve: suspend () -> T,
    ): T? = withContext(Dispatchers.IO) {
        cached()?.let { return@withContext it }
        val fetch = needsFetch()
        // A synchronous storage probe may finish after the user skips the track.
        currentCoroutineContext().ensureActive()
        if (fetch) resolve() else null
    }
}
