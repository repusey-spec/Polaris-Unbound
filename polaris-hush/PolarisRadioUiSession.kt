package app.hush.music.polaris

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Do not show an inactive restored 89.1 station as a user-selected programme. */
object PolarisRadioUiSession {
    var hasStartedRadio by mutableStateOf(false)
        private set
    fun onIsPlayingChanged(isPlaying: Boolean, mediaId: String?) {
        if (isPlaying && mediaId?.startsWith("polaris:station:") == true) hasStartedRadio = true
    }
}
