package app.hush.music.polaris

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object PolarisDownloadRetry {
    suspend fun <T> run(id: String, report: (Int, IOException) -> Unit, invalidate: () -> Unit, transfer: suspend () -> T): T {
        for (attempt in 1..3) {
            try { return transfer() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) {
                if (!PolarisDownloadPolicy.mayRetry(error)) throw error
                report(attempt, error)
                if (attempt == 3) throw error
                invalidate()
                delay(attempt * 1000L)
            }
        }
        error("unreachable download retry $id")
    }
}

