package app.hush.music.lite

import android.content.Context
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.PlaybackAuthState
import app.hush.music.innertube.models.YouTubeLocale
import java.util.Locale

/**
 * Account state is restored once per process before search or playback.
 * Persistent state is encrypted by Android Keystore and limited to the
 * original InnerTube cookie, DataSyncId and visitorData fields.
 */
object LiteSession {
    @Volatile private var loaded = false

    @Synchronized
    fun initialize(context: Context) {
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = locale.country.ifBlank { "KR" },
            hl = locale.language.ifBlank { "ko" }
        )
        if (loaded) return
        LiteSessionStore.restore(context)?.let { YouTube.authState = it }
        loaded = true
    }

    /**
     * Does not persist a tentative login: caller first verifies
     * YouTube.accountInfo() with the submitted credentials.
     */
    fun setAccount(cookie: String, dataSyncId: String?, visitorData: String? = null) {
        YouTube.authState = PlaybackAuthState(
            cookie = cookie,
            dataSyncId = dataSyncId?.takeIf { it.isNotBlank() },
            visitorData = visitorData?.takeIf { it.isNotBlank() },
        ).normalized()
    }

    fun persistVerifiedAccount(context: Context): Boolean =
        LiteSessionStore.save(context, YouTube.authState)

    @Synchronized
    fun clear(context: Context) {
        LiteSessionStore.clear(context)
        YouTube.authState = PlaybackAuthState.EMPTY
        loaded = true
    }
}
