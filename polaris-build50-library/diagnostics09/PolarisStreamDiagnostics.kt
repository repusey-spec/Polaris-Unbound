package app.hush.music.polaris

import android.content.Context
import app.hush.music.innertube.PlaybackTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Separate bounded ring so large queue dumps cannot erase the client diagnosis. */
object PolarisStreamDiagnostics {
    private const val PREFS = "polaris_stream_diagnostics09"
    private const val LIMIT = 72_000
    @Volatile private var app: Context? = null
    fun install(context: Context) {
        app = context.applicationContext
        PlaybackTrace.sink = ::record
    }
    @Synchronized private fun record(event: String) {
        runCatching {
            val context = app ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val previous = prefs.getString("events", "").orEmpty()
            val joined = previous + "$time ${event.take(2000)}\n"
            val bounded = if (joined.length <= LIMIT) joined else joined.takeLast(LIMIT).substringAfter('\n', "")
            prefs.edit().putString("events", bounded).apply()
        }
    }
    fun dump(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("events", "아직 스트림 요청 기록이 없습니다.").orEmpty()
}
