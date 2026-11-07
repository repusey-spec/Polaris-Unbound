package app.hush.music.polaris
import android.content.Context
object PolarisRadioDiagnostics {
    fun record(context: Context, event: String) {
        PolarisDiagnosticJournal.install(context)
        PolarisDiagnosticJournal.record("radio", event)
    }
    fun dump(context: Context): String = PolarisDiagnosticJournal.dump("radio")
}
