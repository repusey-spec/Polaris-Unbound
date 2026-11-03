package app.hush.music.polaris

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hush.music.R
import app.hush.music.playback.DownloadUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Real-file downloads need their own foreground lifetime: Media3 sees no file tasks. */
@AndroidEntryPoint
class PolarisDownloadService : Service() {
    @Inject lateinit var downloads: DownloadUtil
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PolarisErrorReport.install(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Polaris 다운로드", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.polaris_small_icon).setContentTitle("Polaris 다운로드")
            .setContentText("한 곡씩 다운로드 · 검증 후 다음 곡으로 진행합니다")
            .setOngoing(true).setOnlyAlertOnce(true).build())
        downloads.startSerialDownloads()
        if (observer == null) observer = scope.launch {
            downloads.serialDownloadPending.collectLatest { pending ->
                if (!pending) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        downloads.stopSerialDownloads()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "polaris_serial_downloads_07"
        private const val NOTIFICATION = 507
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PolarisDownloadService::class.java))
        }
    }
}
