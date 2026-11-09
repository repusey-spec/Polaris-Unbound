/* Hush (2026), GPL-3.0. Bounded batch logging shared with Polaris diagnostics. */
package app.hush.music.spotiflac

import android.content.Context
import app.hush.music.polaris.PolarisDiagnosticJournal

object SpotiFLACDiag {
    fun attach(context: Context) = PolarisDiagnosticJournal.install(context)
    fun log(message: String) = PolarisDiagnosticJournal.record("spotiflac", message.take(500))
}
