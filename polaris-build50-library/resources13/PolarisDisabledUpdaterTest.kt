package app.hush.music.polaris

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import app.hush.music.BuildConfig
import app.hush.music.utils.UpdateNotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PolarisDisabledUpdaterTest {
    private fun uninitialized(context: Context) {
        try { WorkManager.getInstance(context); fail("test requires uninitialized WorkManager") }
        catch (_: IllegalStateException) { }
    }
    @Test fun activityStartupCheckWorksWithoutWorkManager() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(BuildConfig.UPDATER_AVAILABLE); uninitialized(context)
        UpdateNotificationManager.checkForUpdates(context)
        uninitialized(context)
    }
    @Test fun scheduleAndCancelDisabledUpdaterDoNotInitializeWorkManager() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(BuildConfig.UPDATER_AVAILABLE); uninitialized(context)
        repeat(20) {
            UpdateNotificationManager.schedulePeriodicUpdateCheck(context)
            UpdateNotificationManager.cancelPeriodicUpdateCheck(context)
        }
        uninitialized(context)
    }
}
