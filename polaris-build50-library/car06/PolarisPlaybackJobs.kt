package app.hush.music.polaris

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob

/** A failed background track must not cancel the service and every other track. */
object PolarisPlaybackJobs {
    fun createServiceJob(): CompletableJob = SupervisorJob()

    suspend fun <T> prefetch(
        mediaId: String,
        report: (String, Exception) -> Unit,
        resolve: suspend () -> T,
    ): T? = try {
        resolve()
    } catch (cancelled: CancellationException) {
        // Manual skips and service shutdown remain real cancellation.
        throw cancelled
    } catch (failure: Exception) {
        report(mediaId, failure)
        null
    }
}
