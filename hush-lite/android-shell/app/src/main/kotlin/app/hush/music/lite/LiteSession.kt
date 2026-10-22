package app.hush.music.lite

import app.hush.music.innertube.YouTube
import app.hush.music.innertube.PlaybackAuthState
import app.hush.music.innertube.models.YouTubeLocale
import java.util.Locale

/** Early integration: credentials remain in process memory only. */
object LiteSession {
    fun setAccount(cookie: String, dataSyncId: String?) {
        YouTube.authState = PlaybackAuthState(
            cookie = cookie,
            dataSyncId = dataSyncId?.takeIf { it.isNotBlank() },
        ).normalized()
    }
    fun initialize() {
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = locale.country.ifBlank { "KR" },
            hl = locale.language.ifBlank { "ko" },
        )
    }
}
