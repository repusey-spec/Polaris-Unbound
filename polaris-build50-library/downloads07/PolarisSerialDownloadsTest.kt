package app.hush.music.polaris

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PolarisSerialDownloadsTest {
    @Test fun aHundredRequestsResolveTransferValidateAndCommitOneAtATime() = runTest {
        val stages = mutableListOf<String>()
        var active = 0; var max = 0
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> fail("unexpected") }) { task ->
            active++; max = maxOf(max, active)
            for (stage in listOf("resolve", "transfer", "validate", "commit")) { stages += "${task.id}:$stage"; delay(1) }
            active--
        }
        repeat(100) { q.enqueue("song$it", "") }; q.start()
        repeat(401) { advanceTimeBy(1); runCurrent() }
        assertEquals(1, max)
        assertEquals((0 until 100).flatMap { id -> listOf("resolve", "transfer", "validate", "commit").map { "song$id:$it" } }, stages)
        assertTrue(q.snapshot().isEmpty()); assertFalse(q.pending.value)
        q.stop()
    }
    @Test fun duplicateRequestsNeverProduceTwoTransfers() = runTest {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> }) { runs++; gate.await() }
        q.enqueue("same", ""); q.start(); runCurrent()
        repeat(50) { q.enqueue("same", "") }; runCurrent()
        assertEquals(1, runs); gate.complete(Unit); runCurrent(); assertTrue(q.snapshot().isEmpty()); q.stop()
    }
    @Test fun aFailedSongDoesNotCancelTheNextSong() = runTest {
        val played = mutableListOf<String>(); val failed = mutableListOf<String>()
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { task, _ -> failed += task.id }) {
            if (it.id == "bad") throw IOException("length mismatch")
            played += it.id
        }
        q.enqueue("bad", ""); q.enqueue("good", ""); q.start(); runCurrent()
        assertEquals(listOf("bad"), failed); assertEquals(listOf("good"), played)
        assertEquals(PolarisSerialDownloads.State.FAILED, q.snapshot().single().state); q.stop()
    }
    @Test fun pauseAndImmediateResumeWaitForOldCleanup() = runTest {
        val stages = mutableListOf<String>(); var count = 0
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> fail("cancel is not error") }) {
            val attempt = ++count; stages += "start$attempt"
            if (attempt == 1) try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(100); stages += "old-cleaned" } }
        }
        q.enqueue("song", ""); q.start(); runCurrent(); q.pause("song"); q.enqueue("song", ""); runCurrent()
        assertEquals(listOf("start1"), stages)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("start1", "old-cleaned", "start2"), stages); q.stop()
    }
    @Test fun removingActiveWorkWaitsBeforeTheNextFile() = runTest {
        val order = mutableListOf<String>()
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> }) {
            order += it.id
            if (it.id == "first") try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(50); order += "cleanup" } }
        }
        q.enqueue("first", ""); q.enqueue("second", ""); q.start(); runCurrent(); q.remove("first"); runCurrent()
        assertEquals(listOf("first"), order); advanceTimeBy(50); runCurrent()
        assertEquals(listOf("first", "cleanup", "second"), order); q.stop()
    }
    @Test fun pausedPendingSongsAreNotTransferred() = runTest {
        val order = mutableListOf<String>()
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> }) { order += it.id }
        q.enqueue("paused", ""); q.pause("paused"); q.enqueue("next", ""); q.start(); runCurrent()
        assertEquals(listOf("next"), order); assertEquals(PolarisSerialDownloads.State.PAUSED, q.snapshot().single().state)
        q.resumeAll(); runCurrent(); assertEquals(listOf("next", "paused"), order); q.stop()
    }
    @Test fun processRestartRestoresInterruptedWorkInOriginalOrder() = runTest {
        val restored = listOf(PolarisSerialDownloads.Task("a", "", state = PolarisSerialDownloads.State.ACTIVE),
            PolarisSerialDownloads.Task("b", ""), PolarisSerialDownloads.Task("paused", "", state = PolarisSerialDownloads.State.PAUSED))
        val order = mutableListOf<String>(); var persisted = emptyList<PolarisSerialDownloads.Task>()
        val q = PolarisSerialDownloads(backgroundScope, restored, persist = { persisted = it }, changed = {}, failed = { _, _ -> }) { order += it.id }
        assertTrue(q.pending.value); q.start(); runCurrent()
        assertEquals(listOf("a", "b"), order); assertEquals("paused", persisted.single().id); q.stop()
    }
    @Test fun stopAndRestartDoNotCreateOverlappingWorkers() = runTest {
        var active = 0; var max = 0; var runs = 0
        val q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> }) {
            val attempt = ++runs; active++; max = maxOf(max, active)
            try { if (attempt == 1) awaitCancellation() }
            finally { withContext(NonCancellable) { delay(50); active-- } }
        }
        q.enqueue("a", ""); q.start(); runCurrent(); q.stop(); q.start(); runCurrent()
        advanceTimeBy(50); runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(1, max); assertEquals(2, runs); q.stop()
    }
    @Test fun diskOrNetworkHoldCanPauseRemainingQueueWithoutCancellingCurrentFailure() = runTest {
        val order = mutableListOf<String>()
        lateinit var q: PolarisSerialDownloads
        q = PolarisSerialDownloads(backgroundScope, persist = {}, changed = {}, failed = { _, _ -> q.pauseAll() }) {
            order += it.id; throw IOException("ENOSPC")
        }
        q.enqueue("first", ""); q.enqueue("second", ""); q.start(); runCurrent()
        assertEquals(listOf("first"), order)
        assertEquals(PolarisSerialDownloads.State.PAUSED, q.snapshot().last().state); assertFalse(q.pending.value); q.stop()
    }
}
