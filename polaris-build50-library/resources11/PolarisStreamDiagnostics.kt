package app.hush.music.polaris
import android.content.Context
import app.hush.music.innertube.PlaybackTrace
object PolarisStreamDiagnostics {
    fun install(context: Context) {
        PolarisDiagnosticJournal.install(context)
        PlaybackTrace.sink = { PolarisDiagnosticJournal.record("stream", it) }
    }
    fun dump(context: Context): String = PolarisDiagnosticJournal.dump("stream")
}
