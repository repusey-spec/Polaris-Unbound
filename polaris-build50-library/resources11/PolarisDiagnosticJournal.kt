package app.hush.music.polaris

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Shared, single writer. Callers enqueue short records; flush never runs on a playback callback. */
object PolarisDiagnosticJournal {
    private const val FILE_LIMIT = 256_000
    private val queues = listOf("playback", "stream", "radio", "resource").associateWith { PolarisBoundedEvents() }
    private val installed = AtomicBoolean()
    private val error = java.util.concurrent.atomic.AtomicReference<String?>(null)
    @Volatile private var savedError: String? = null
    private val diskLock = Any()
    @Volatile private var directory: File? = null
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PolarisJournal").apply { isDaemon = true } }
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        writer.execute {
            runCatching {
                directory = File(app.filesDir, "polaris-diagnostics11").apply { mkdirs() }
                // Legacy diagnostics are read only once off Main; new builds never apply per event.
                val marker = File(directory, "legacy-imported")
                if (!marker.exists()) {
                    for ((channel, prefs) in listOf("playback" to "polaris_error_report_04", "stream" to "polaris_stream_diagnostics09", "radio" to "polaris_radio_aa_diagnostics")) {
                        val old = app.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString("events", "").orEmpty().takeLast(FILE_LIMIT)
                        if (old.isNotEmpty()) append(channel, "[previous builds]\n$old\n")
                    }
                    marker.writeText("1")
                }
            }
        }
        writer.scheduleWithFixedDelay({ flush() }, 2, 2, TimeUnit.SECONDS)
    }
    fun record(channel: String, event: String) {
        val safe = PolarisSourcePolicy.redact(event.take(4_000))
        queues[channel]?.add("wall=${System.currentTimeMillis()} mono=${SystemClock.elapsedRealtime()} $safe")
    }
    private fun append(channel: String, value: String) {
        val dir = directory ?: return
        synchronized(diskLock) {
            val target = File(dir, "$channel.log")
            if (target.length() + value.toByteArray(Charsets.UTF_8).size > FILE_LIMIT) {
                val previous = File(dir, "$channel.previous.log")
                previous.delete()
                if (target.exists() && !target.renameTo(previous)) target.delete()
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            target.appendBytes(if (bytes.size <= FILE_LIMIT) bytes else bytes.copyOfRange(bytes.size - FILE_LIMIT, bytes.size))
        }
    }
    private fun flush() {
        if (directory == null) return
        runCatching {
            error.get()?.takeIf { it != savedError }?.let { value ->
                val file = File(directory, "last-error.txt")
                val temporary = File(directory, "last-error.tmp")
                temporary.writeText(value.take(20_000))
                if (temporary.renameTo(file)) savedError = value
            }
        }
        queues.forEach { (channel, queue) ->
            runCatching { val batch = queue.drain(); if (batch.isNotEmpty()) append(channel, batch) }
        }
    }
    fun rememberError(value: String) { error.set(value.take(20_000)) }
    fun lastError(): String? = error.get() ?: runCatching {
        val dir = directory ?: return@runCatching null
        val f = File(dir, "last-error.txt")
        if (f.exists()) f.readText().take(20_000) else null
    }.getOrNull()
    fun dump(channel: String): String = synchronized(diskLock) {
        val dir = directory
        buildString {
            if (dir != null) for (name in listOf("$channel.previous.log", "$channel.log")) {
                runCatching { val f = File(dir, name); if (f.exists()) append(f.readText().takeLast(FILE_LIMIT)) }
            }
            append(queues[channel]?.snapshot().orEmpty())
        }
    }
}
