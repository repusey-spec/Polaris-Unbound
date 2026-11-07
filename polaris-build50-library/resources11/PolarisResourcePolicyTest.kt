package app.hush.music.polaris

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PolarisResourcePolicyTest {
    @Test fun burstRemainsBoundedAndReportsLostRecords() {
        val queue = PolarisBoundedEvents(1000, 8)
        repeat(100_000) { queue.add("record-$it-" + "x".repeat(100)) }
        assertTrue(queue.retainedChars() <= 1000)
        assertTrue(queue.retainedEntries() <= 8)
        val flushed = queue.drain()
        assertTrue(flushed.contains("dropped="))
        assertTrue(flushed.contains("record-99999-"))
        assertEquals(0, queue.retainedEntries())
        assertEquals("", queue.drain())
    }
    @Test fun concurrentProducersAndDrainDoNotExceedBounds() {
        val queue = PolarisBoundedEvents(1000, 10)
        val producers = (0..3).map { Thread { repeat(2000) { queue.add("event-$it") } } }
        producers.forEach { it.start() }
        repeat(100) { queue.drain() }
        producers.forEach { it.join() }
        assertTrue(queue.retainedChars() <= 1000)
        assertTrue(queue.retainedEntries() <= 10)
    }
    @Test fun oversizedRecordDoesNotBreakBounds() {
        val queue = PolarisBoundedEvents(64, 2)
        queue.add("x".repeat(10_000)); assertEquals(64, queue.retainedChars())
    }
    @Test fun healthyLocalPlaybackIsNeverSeekedOnDeviceInventoryChange() {
        assertFalse(PolarisAudioRoutePolicy.needsSeek(true, true, false, false))
        assertFalse(PolarisAudioRoutePolicy.needsSeek(true, true, true, false))
        assertFalse(PolarisAudioRoutePolicy.needsSeek(false, false, true, false))
        assertFalse(PolarisAudioRoutePolicy.needsSeek(false, true, false, true))
        assertTrue(PolarisAudioRoutePolicy.needsSeek(false, true, true, false))
    }
    @Test fun metadataCacheEvictsOldEntriesAndKeepsRecentlyUsed() {
        val cache = PolarisSmallCache<Int>(3)
        cache["a"]=1;cache["b"]=2;cache["c"]=3
        assertEquals(1, cache["a"])
        cache["d"]=4
        assertNull(cache["b"]); assertEquals(3, cache.size()); assertEquals(1, cache["a"])
    }
    @Test fun cancelledMintImmediatelyRemovesWaiter() = runBlocking {
        val pending = PolarisPendingRequests<String>()
        val entered = CompletableDeferred<Unit>()
        val task = launch { suspendCancellableCoroutine<String> { pending.add(it); entered.complete(Unit) } }
        entered.await(); assertEquals(1,pending.size)
        task.cancelAndJoin(); assertEquals(0,pending.size)
    }
    @Test fun simultaneousRequestsNeverOverwriteEachOther() = runBlocking {
        val pending = PolarisPendingRequests<String>()
        val ids = mutableListOf<String>()
        val first = async(start=CoroutineStart.UNDISPATCHED) { suspendCancellableCoroutine<String> { ids += pending.add(it) } }
        val second = async(start=CoroutineStart.UNDISPATCHED) { suspendCancellableCoroutine<String> { ids += pending.add(it) } }
        assertNotEquals(ids[0],ids[1]); assertEquals(2,pending.size)
        pending.complete(ids[1],"second");pending.complete(ids[0],"first")
        assertEquals("first",first.await());assertEquals("second",second.await());assertEquals(0,pending.size)
    }
    @Test fun engineCloseCancelsEveryWaiterAndLateCallbackIsIgnored() = runBlocking {
        val pending = PolarisPendingRequests<String>()
        var id=""
        val task=async(start=CoroutineStart.UNDISPATCHED) { suspendCancellableCoroutine<String> { id=pending.add(it) } }
        pending.cancelAll();pending.complete(id,"late")
        assertEquals(0,pending.size)
        withTimeout(5_000) { task.join() }
        assertTrue(task.isCancelled)
    }
    @Test fun decipherFailureExpiresAfterShortRecoveryWindow() {
        app.hush.music.utils.StreamClientAvailability.forgetAll()
        app.hush.music.utils.StreamClientAvailability.markDecipherUnavailable(100_000)
        assertFalse(app.hush.music.utils.StreamClientAvailability.canDecipher(100_001))
        assertTrue(app.hush.music.utils.StreamClientAvailability.canDecipher(130_001))
        app.hush.music.utils.StreamClientAvailability.forgetAll()
    }
}
