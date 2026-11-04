package app.hush.music.polaris

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PolarisDownloadMigrationTest {
    @Test fun existing507FailedQueueIsVisibleAfterUpgradeWithoutReenqueue() {
        val context=RuntimeEnvironment.getApplication()
        val f=File(context.filesDir,"polaris_download_queue_07.json")
        File(f.path+".bak").delete()
        f.writeText("""[{"id":"failed","title":"곡","token":"old-attempt","state":"FAILED"}]""")
        val row=PolarisDownloadStore(context).read().single()
        assertEquals(PolarisSerialDownloads.State.FAILED,row.state)
        assertEquals("old-attempt",row.token);assertEquals(1,row.attempts)
        assertTrue(row.error.isNotBlank());assertEquals(0L,row.updatedAt)
        f.delete()
    }
    @Test fun detailedFailureRoundTripsThroughRealAtomicFile() {
        val store=PolarisDownloadStore(RuntimeEnvironment.getApplication())
        val row=PolarisSerialDownloads.Task("bad","곡",state=PolarisSerialDownloads.State.FAILED,
            attempts=3,updatedAt=123,stage="검증",error="검증 시간 초과",bytes=4200,expected=4200,tempPath="audio.part")
        store.write(listOf(row));assertEquals(row,store.read().single());store.write(emptyList())
    }
    @Test fun safeFailureClearDoesNotTouchThePreviouslySavedFile() {
        val context=RuntimeEnvironment.getApplication();val dir=context.filesDir
        val saved=File(dir,"good.webm").apply{writeText("existing good audio")}
        val part=File(dir,"good.webm.part").apply{writeText("incomplete")}
        val task=PolarisSerialDownloads.Task("bad","",state=PolarisSerialDownloads.State.FAILED,tempPath=part.absolutePath)
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val q=PolarisSerialDownloads(scope,listOf(task),persist={},changed={},failed={_,_->}){}
        assertTrue(q.discardFailed("bad"){File(it.tempPath).delete()})
        assertEquals("existing good audio",saved.readText());assertFalse(part.exists());assertTrue(q.entries.value.isEmpty())
        saved.delete()
    }
}
