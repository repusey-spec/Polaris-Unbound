package app.hush.music.polaris

import app.hush.music.innertube.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class PolarisResolverCancellationTest {
    @After fun cleanup() { PlaybackTrace.sink = null }

    @Test fun swallowedResultDoesNotStartAnotherClientAfterDeadline() = runBlocking {
        var clients = 0
        val result = withTimeoutOrNull(30) {
            repeat(20) {
                StreamResolutionControl.attempt {
                    clients++
                    // Reproduce the existing lower API that catches cancellation into Result.
                    runCatching { delay(500) }.getOrNull()
                }
            }
        }
        assertNull(result)
        assertEquals(1, clients)
    }
    @Test fun nestedDownloadQualityAndClientLoopsStopTogether() = runBlocking {
        var wire = 0
        withTimeoutOrNull(30) {
            StreamResolutionControl.attempt {
                repeat(12) {
                    StreamResolutionControl.attempt {
                        repeat(3) {
                            StreamResolutionControl.attempt {
                                wire++
                                runCatching { delay(500) }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(1, wire)
    }
    @Test fun externalCancellationRemainsCancellation() = runBlocking {
        var completedNormally = false
        val child = launch {
            StreamResolutionControl.attempt { delay(5_000) }
            completedNormally = true
        }
        yield(); child.cancelAndJoin()
        assertFalse(completedNormally)
    }
    @Test fun ordinaryFailureCanStillUseFallback() = runBlocking {
        val error = IllegalStateException("ordinary failure")
        val first = StreamResolutionControl.attempt<Int> { throw error }
        assertSame(error, first.exceptionOrNull())
        assertEquals(7, StreamResolutionControl.attempt { 7 }.getOrThrow())
    }
    @Test fun blockingConverterCanBeInterrupted() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var after = false
        val child = launch(Dispatchers.Default) {
            StreamResolutionControl.attempt {
                runInterruptible(Dispatchers.IO) {
                    entered.complete(Unit)
                    Thread.sleep(5_000)
                }
                after = true
            }
        }
        entered.await(); child.cancelAndJoin()
        assertFalse(after)
    }
    @Test fun signatureFailureCategoriesNeverCopyCredentials() {
        val events = mutableListOf<String>(); PlaybackTrace.sink = events::add
        val error = IllegalStateException("wrapper signed.invalid?cookie=SECRET", IllegalArgumentException("Could not find deobfuscation function SECRET"))
        StreamResolutionControl.candidate("VVUvWmHAWDQ", "WEB_REMIX", 251, "conversion-failed", true, error)
        assertTrue(events.single().contains("category=signature-extractor"))
        assertFalse(events.single().contains("SECRET"))
        assertFalse(events.single().contains("signed.invalid"))
    }
    @Test fun gvsFailureAndCipherCooldownAreDistinct() {
        assertEquals("음원 전송 토큰 준비 실패", StreamCandidateException("missing-gvs-token").message)
        assertEquals("음원 주소 서명 변환 실패", StreamCandidateException("decipher-cooldown").message)
    }
    @Test fun localSignatureCauseSurvivesDownloadReason() {
        val original = IllegalArgumentException("deobfuscation failed")
        val failure = StreamCandidateException("signature-extractor", original)
        assertEquals("음원 주소 서명 변환 실패", PolarisDownloadPolicy.reason(failure))
        assertSame(original, failure.cause)
        assertFalse(PolarisDownloadPolicy.mayRetry(failure))
    }
    @Test fun stageDoesNotEmitAfterCancellation() = runBlocking {
        val events = mutableListOf<String>(); PlaybackTrace.sink = events::add
        val child = launch {
            currentCoroutineContext().cancel()
            runCatching { PlaybackTrace.stage("metadata-start", "VVUvWmHAWDQ") }
        }
        child.join(); assertTrue(events.isEmpty())
    }
    @Test fun causeClassificationHasBoundedTraversal() {
        val first = IllegalStateException("ordinary")
        val second = IllegalStateException("ordinary", first)
        first.initCause(second)
        assertEquals("url-conversion", StreamResolutionControl.category(first))
    }
}
