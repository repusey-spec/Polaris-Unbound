package app.hush.music.polaris

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Reused by both the Radio and Streaming AA custom playback actions. */
object PolarisAAModeCommand {
    const val ACTION = "app.hush.music.polaris.SWITCH_MEDIA_MODE"
    val command: SessionCommand = SessionCommand(ACTION, Bundle.EMPTY)
}
