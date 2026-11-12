package app.hush.music.polaris

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PolarisLocalPrefetchGateTest {
    @Test fun cachedUrlSkipsStorageAndNetwork() = runBlocking {
        assertEquals("cached", PolarisLocalPrefetchGate.lookup({ "cached" }, { error("storage") }, { error("network") }))
    }
    @Test fun localFileSkipsNetwork() = runBlocking {
        assertNull(PolarisLocalPrefetchGate.lookup<String>({ null }, { false }, { error("network") }))
    }
    @Test fun missingFileResolvesOnce() = runBlocking {
        var probes = 0; var resolves = 0
        assertEquals("remote", PolarisLocalPrefetchGate.lookup({ null }, { probes++; true }, { resolves++; "remote" }))
        assertEquals(1, probes); assertEquals(1, resolves)
    }
    @Test fun cacheStorageAndResolverAllLeaveCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        PolarisLocalPrefetchGate.lookup({ assertNotSame(caller, Thread.currentThread()); null },
            { assertNotSame(caller, Thread.currentThread()); true },
            { assertNotSame(caller, Thread.currentThread()); "remote" })
        Unit
    }
    @Test fun slowDiskDoesNotBlockCallerEventLoop() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val job = async {
            PolarisLocalPrefetchGate.lookup<String>({ null }, {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                false
            }, { error("network") })
        }
        try {
            withTimeout(3000) { entered.await() }
            // This continuation runs on the same event loop that launched the probe.
            assertFalse(job.isCompleted)
        } finally { release.countDown() }
        assertNull(job.await())
    }
    @Test fun skipDuringDiskProbeNeverStartsNetwork() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val resolved = AtomicBoolean(false)
        val job = launch {
            PolarisLocalPrefetchGate.lookup({ null }, {
                entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); true
            }, { resolved.set(true); "remote" })
        }
        try { withTimeout(3000) { entered.await() }; job.cancel() }
        finally { release.countDown() }
        job.join(); assertTrue(job.isCancelled); assertFalse(resolved.get())
    }
    @Test fun cancelledCallerDoesNotProbeDisk() = runBlocking {
        val probed = AtomicBoolean(false)
        val job = launch(start = CoroutineStart.LAZY) {
            PolarisLocalPrefetchGate.lookup<String>({ null }, { probed.set(true); true }, { "remote" })
        }
        job.cancelAndJoin(); assertFalse(probed.get())
    }
    @Test fun storageFailureIsNotMisreportedAsLocalSuccess() = runBlocking {
        val failure = IllegalStateException("storage unavailable")
        try {
            PolarisLocalPrefetchGate.lookup<String>({ null }, { throw failure }, { error("network") })
            fail("must propagate to existing prefetch failure handler")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may copy an exception across dispatchers.
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
    }
}
