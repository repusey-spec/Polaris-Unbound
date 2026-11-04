package app.hush.music.polaris

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PolarisFailedDownloadsTest {
    @Test fun aFailedItemRemainsVisibleWithCauseAndAttemptCount() = runTest {
        val q=PolarisSerialDownloads(backgroundScope,persist={},changed={},failed={_,_->}) { throw PolarisResolutionTimeout() }
        q.enqueue("bad","title");q.start();runCurrent()
        val row=q.entries.value.single()
        assertEquals(PolarisSerialDownloads.State.FAILED,row.state);assertEquals("주소 조회 시간 초과",row.error)
        assertEquals(1,row.attempts);assertFalse(q.pending.value);q.stop()
    }
    @Test fun retryOnlyFailedAndPreserveSingleWorker() = runTest {
        var bad=true;var active=0;var max=0;val runs=mutableListOf<String>()
        val q=PolarisSerialDownloads(backgroundScope,persist={},changed={},failed={_,_->}) {
            active++;max=maxOf(max,active)
            try { runs+=it.id;if(it.id=="bad" && bad) throw IOException();delay(10) } finally {active--}
        }
        q.enqueue("bad","");q.enqueue("good","");q.start();runCurrent();advanceUntilIdle();advanceTimeBy(20);runCurrent()
        bad=false;assertTrue(q.retryFailed("bad") {});assertFalse(q.retryFailed("good") {})
        runCurrent();advanceTimeBy(20);runCurrent()
        assertEquals(1,max);assertEquals(listOf("bad","good","bad"),runs);assertTrue(q.entries.value.isEmpty());q.stop()
    }
    @Test fun clearFailedNeverRemovesQueuedOrActiveWork() = runTest {
        val q=PolarisSerialDownloads(backgroundScope,persist={},changed={},failed={_,_->}) { awaitCancellation() }
        q.enqueue("active","");q.enqueue("waiting","");q.start();runCurrent()
        var touched=false
        assertFalse(q.discardFailed("active") {touched=true});assertFalse(q.discardFailed("waiting") {touched=true})
        assertFalse(touched);assertEquals(2,q.entries.value.size);q.stop()
    }
    @Test fun failedCleanupFailureKeepsTheFailureRecord() = runTest {
        val task=PolarisSerialDownloads.Task("bad","",state=PolarisSerialDownloads.State.FAILED,tempPath="file.part")
        val q=PolarisSerialDownloads(backgroundScope,listOf(task),persist={},changed={},failed={_,_->}) {}
        try {q.discardFailed("bad"){throw IOException("permission")};fail()}catch(_:IOException){}
        assertEquals(task,q.entries.value.single())
    }
    @Test fun retryAndDeleteRaceCannotDeleteTheNewAttempt() = runTest {
        val old=PolarisSerialDownloads.Task("bad","",state=PolarisSerialDownloads.State.FAILED)
        val q=PolarisSerialDownloads(backgroundScope,listOf(old),persist={},changed={},failed={_,_->}) {awaitCancellation()}
        assertTrue(q.retryFailed("bad"){});q.start();runCurrent()
        assertFalse(q.discardFailed("bad"){});assertEquals(2,q.entries.value.single().attempts)
        assertNotEquals(old.token,q.entries.value.single().token);q.stop()
    }
    @Test fun phaseAndPartialFileMetadataSurviveProcessRestoration() = runTest {
        var saved=emptyList<PolarisSerialDownloads.Task>()
        val q=PolarisSerialDownloads(backgroundScope,persist={saved=it},changed={},failed={_,_->}) {
            qProgress?.invoke();throw PolarisVerificationTimeout()
        }
        qProgress={q.progress("bad","검증",123,123,"audio.part")}
        q.enqueue("bad","");q.start();runCurrent();q.stop();qProgress=null
        val restored=PolarisSerialDownloads(backgroundScope,saved,persist={},changed={},failed={_,_->}){}
        assertEquals("검증",restored.entries.value.single().stage)
        assertEquals("audio.part",restored.entries.value.single().tempPath)
        assertEquals("검증 시간 초과 · 재시도 가능",restored.entries.value.single().error)
    }
    private var qProgress:(()->Unit)?=null
}
