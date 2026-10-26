package app.hush.music.polaris

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded local diagnostics; no URLs, voice queries or location are logged. */
object PolarisRadioDiagnostics {
    private const val PREFS = "polaris_radio_aa_diagnostics"
    private const val KEY = "events"
    private const val MAX_LINES = 175

    @Synchronized
    fun record(context: Context, event: String) {
        val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = clock + " " + event.take(220).replace("\n", " ")
        Log.i("PolarisRadioDiag", line)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY, "").orEmpty()
        val previousLines = previous.lineSequence().filter { it.isNotBlank() }.toList()
        prefs.edit().putString(KEY, (previousLines.takeLast(MAX_LINES - 1) + line).joinToString("\n")).apply()
    }

    @Synchronized
    fun dump(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "기록된 이벤트가 없습니다.").orEmpty()
}
