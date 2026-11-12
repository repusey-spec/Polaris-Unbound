package app.hush.music.polaris

import android.net.Uri
import androidx.media3.datasource.DataSpec

internal object PolarisOnlineRecovery {
    fun remoteSpec(original: DataSpec, mediaId: String, remote: Uri): DataSpec {
        require(remote.scheme == "https" || remote.scheme == "http")
        return original.buildUpon().setUri(remote).setKey("polaris-online14:" + mediaId).build()
    }
}
