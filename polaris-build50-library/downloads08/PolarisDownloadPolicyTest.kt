package app.hush.music.polaris

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PolarisDownloadPolicyTest {
    @Test fun resolutionTimesOutAndReleasesItsChild() = runTest {
        var cleaned = false
        try {
            PolarisDownloadPolicy.resolve(30_000, { testScheduler.currentTime }) {
                try { delay(60_000) } finally { cleaned = true }
            }
            fail("resolution deadline missing")
        } catch (_: PolarisResolutionTimeout) {}
        assertEquals(30_000L, testScheduler.currentTime); assertTrue(cleaned)
    }
    @Test fun providerFallbackUsesRemainingTimeNotAnotherThirtySeconds() = runTest {
        PolarisDownloadPolicy.resolve(30_000, { testScheduler.currentTime }) { delay(25_000) }
        try { PolarisDownloadPolicy.resolve(30_000, { testScheduler.currentTime }) { delay(20_000) }; fail() }
        catch (_: PolarisResolutionTimeout) {}
        assertEquals(30_000L, testScheduler.currentTime)
    }
    @Test fun userCancellationIsNeverReportedAsDownloadFailure() = runTest {
        var converted = false
        val job = launch {
            try { PolarisDownloadPolicy.resolve(30_000, { testScheduler.currentTime }) { awaitCancellation() } }
            catch (_: PolarisResolutionTimeout) { converted = true }
        }
        runCurrent(); job.cancelAndJoin(); assertFalse(converted)
    }
    @Test fun expiredBudgetNeverStartsAnotherProvider() = runTest {
        var started = false
        try { PolarisDownloadPolicy.resolve(0, { 1 }) { started = true }; fail() } catch (_: PolarisResolutionTimeout) {}
        assertFalse(started)
    }
    @Test fun timeoutAndVerificationAreNotRetriedAsOrdinaryIo() {
        assertFalse(PolarisDownloadPolicy.mayRetry(PolarisResolutionTimeout()))
        assertFalse(PolarisDownloadPolicy.mayRetry(PolarisVerificationTimeout()))
        assertTrue(PolarisDownloadPolicy.mayRetry(IOException("connection reset")))
    }
    @Test fun enabledSkipsAndDisabledStopsForTheSameFinalFailure() {
        assertEquals(PolarisPlaybackFailurePolicy.Decision.SKIP, PolarisPlaybackFailurePolicy.decide(true,true,true,0,0,1))
        assertEquals(PolarisPlaybackFailurePolicy.Decision.STOP, PolarisPlaybackFailurePolicy.decide(false,true,true,0,0,1))
    }
    @Test fun noNextOrRepeatOfSameItemCannotSpin() {
        assertEquals(PolarisPlaybackFailurePolicy.Decision.STOP, PolarisPlaybackFailurePolicy.decide(true,true,true,0,0,0))
        assertEquals(PolarisPlaybackFailurePolicy.Decision.STOP, PolarisPlaybackFailurePolicy.decide(true,true,true,0,0,-1))
        assertEquals(PolarisPlaybackFailurePolicy.Decision.STOP, PolarisPlaybackFailurePolicy.decide(true,true,true,5,0,1))
    }
    @Test fun offlineAndUserStopDoNotSkipThroughTheCollection() {
        assertEquals(PolarisPlaybackFailurePolicy.Decision.WAIT, PolarisPlaybackFailurePolicy.decide(true,false,true,0,0,1))
        assertEquals(PolarisPlaybackFailurePolicy.Decision.STOP, PolarisPlaybackFailurePolicy.decide(true,true,false,0,0,1))
    }
    @Test fun storedHushReleaseCannotAdvertiseAnUpdateInPolaris() {
        assertFalse(app.hush.music.BuildConfig.UPDATER_AVAILABLE)
        assertFalse(app.hush.music.utils.Updater.isUpdateAvailable("v13.14.8", "50.8"))
    }
    @Test fun readyOrUnmovingDamagedFileDoesNotResetTheRetryBudget() {
        assertFalse(PolarisPlaybackFailurePolicy.recovered("a","a",true,false,1000,1000))
        assertFalse(PolarisPlaybackFailurePolicy.recovered("a","a",true,true,1000,1001))
    }
    @Test fun lateRecoveryCannotOverrideUserStopOrTrackChange() {
        assertFalse(PolarisPlaybackFailurePolicy.recovered("a","b",true,true,1000,3000))
        assertFalse(PolarisPlaybackFailurePolicy.recovered("a","a",false,true,1000,3000))
    }
    @Test fun actualProgressResetsRecoveryBudget() {
        assertTrue(PolarisPlaybackFailurePolicy.recovered("a","a",true,true,1000,2500))
    }
}
