package app.hush.music.polaris

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PolarisDownloadRetryTest {
    @Test fun retriesHaveAnUpperBoundAndKeepOriginalFailure() = runTest {
        var runs = 0; var invalidated = 0; val attempts = mutableListOf<Int>(); val original = IOException("disconnected")
        try {
            PolarisDownloadRetry.run<Unit>("song", { n, _ -> attempts += n }, { invalidated++ }) { runs++; throw original }
            fail("must fail after bounded retries")
        } catch (e: IOException) { assertSame(original, e) }
        assertEquals(3, runs); assertEquals(2, invalidated); assertEquals(listOf(1, 2, 3), attempts)
    }
    @Test fun retryCanRecoverAndCommitOnlyTheSuccessfulAttempt() = runTest {
        var runs = 0
        assertEquals("whole", PolarisDownloadRetry.run("song", { _, _ -> }, {}) {
            if (++runs == 1) throw IOException("short")
            "whole"
        })
        assertEquals(2, runs)
    }
    @Test fun manualCancellationNeverRetries() = runTest {
        var runs = 0
        try { PolarisDownloadRetry.run<Unit>("song", { _, _ -> fail("not IO") }, { fail("no retry") }) {
            runs++; throw CancellationException("paused")
        }; fail("cancel") } catch (_: CancellationException) { }
        assertEquals(1, runs)
    }
}
