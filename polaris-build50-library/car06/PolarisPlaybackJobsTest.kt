package app.hush.music.polaris

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PolarisPlaybackJobsTest {
    @Test fun failingTrackDoesNotCancelSharedMainAndIoWork() = runTest {
        val parent = PolarisPlaybackJobs.createServiceJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = CoroutineScope(dispatcher + parent)
        val io = CoroutineScope(dispatcher + parent)
        try {
            val challenged = io.async<String> { throw IOException("address challenged PV1MJ4Pcvn8") }
            val current = io.async { "u2RdlY-X9UU" }
            val ui = main.async { "controls alive" }
            runCurrent()
            assertTrue(parent.isActive)
            assertEquals("u2RdlY-X9UU", current.await())
            assertEquals("controls alive", ui.await())
            try { challenged.await(); fail("Expected the original track error") }
            catch (expected: IOException) { assertTrue(expected.message!!.contains("PV1MJ4Pcvn8")) }
        } finally { parent.cancel() }
    }
    @Test fun aLaterTrackCanResolveAfterAnotherTrackFailed() = runTest {
        val parent = PolarisPlaybackJobs.createServiceJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        try {
            val failure = scope.async<String> { throw IOException("login required for one track") }
            runCurrent()
            runCatching { failure.await() }
            val later = scope.async { "fresh stream" }
            runCurrent()
            assertEquals("fresh stream", later.await())
            assertTrue(parent.isActive)
        } finally { parent.cancel() }
    }
    @Test fun backgroundFailureIsReportedWithItsOwnIdAndReturnsNoUrl() = runTest {
        val reported = mutableListOf<String>()
        val result = PolarisPlaybackJobs.prefetch<String>("PV1MJ4Pcvn8", { id, _ -> reported += id }) {
            throw IOException("address rejected")
        }
        assertNull(result)
        assertEquals(listOf("PV1MJ4Pcvn8"), reported)
    }
    @Test fun manualCancellationIsNotSwallowedOrMisreportedAsSourceFailure() = runTest {
        var reported = false
        try {
            PolarisPlaybackJobs.prefetch<String>("test", { _, _ -> reported = true }) {
                throw CancellationException("user skipped")
            }
            fail("Expected cancellation")
        } catch (expected: CancellationException) { assertEquals("user skipped", expected.message) }
        assertFalse(reported)
    }
}
