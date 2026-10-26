package app.hush.music.polaris

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Port of Polaris v0.58's RadioTalkLoadErrorPolicy.
 *
 * Only Polaris radio uses the 100 ms segment/playlist load retry loop.
 * Hush Streaming preserves its original Media3 default policy.
 *
 * This flag is updated on the playback application thread by MusicService's
 * onMediaItemTransition, and read by Media3 loading threads.
 */
class PolarisRadioLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    @Volatile var radioActive: Boolean = false

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (!radioActive) return super.getRetryDelayMsFor(info)

        val error = info.exception
        if (error is HttpDataSource.InvalidResponseCodeException &&
            (error.responseCode == 401 || error.responseCode == 403)
        ) {
            Log.w("PolarisRadio", "Radio HTTP ${error.responseCode}: refresh broadcaster URL")
            return C.TIME_UNSET
        }

        val defaultDelay = super.getRetryDelayMsFor(info)
        if (defaultDelay == C.TIME_UNSET) return defaultDelay

        // A transient HLS segment failure should NOT cause a complete stream restart.
        // Matches v0.58's 100 ms retry for approximately 10 seconds.
        if (info.errorCount == 1 || info.errorCount == 10 ||
            info.errorCount == 50 || info.errorCount == 101
        ) {
            Log.w("PolarisRadio", "RadioTalk retry #${info.errorCount}: ${error.javaClass.simpleName}")
        }
        return 100L
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (radioActive) 101 else super.getMinimumLoadableRetryCount(dataType)
}
