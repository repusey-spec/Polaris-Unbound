package app.hush.music.polaris

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PolarisDownloadStoreTest {
    @Test fun realAtomicQueueStorePreservesOrderPauseAndAttemptIds() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "polaris_download_queue_07.json"); file.delete()
        val tasks = listOf(PolarisSerialDownloads.Task("first", "곡 1", "token1", PolarisSerialDownloads.State.ACTIVE),
            PolarisSerialDownloads.Task("second", "곡 2", "token2", PolarisSerialDownloads.State.PAUSED))
        PolarisDownloadStore(context).write(tasks)
        assertEquals(tasks, PolarisDownloadStore(context).read())
        PolarisDownloadStore(context).write(emptyList())
        assertTrue(PolarisDownloadStore(context).read().isEmpty())
    }
}
