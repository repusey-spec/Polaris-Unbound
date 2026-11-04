package app.hush.music.polaris

import android.content.*
import android.os.*
import android.system.Os
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PolarisDownloadValidation {
    suspend fun validate(context: Context, file: File, knownLength: Boolean, keepGoing: () -> Boolean) {
        if (!keepGoing()) throw CancellationException("download validation cancelled")
        val started = SystemClock.elapsedRealtime()
        try {
            val evidence = withTimeout(PolarisDownloadPolicy.PROBE_MS + 2_000L) {
                withContext(Dispatchers.Main.immediate) { probe(context.applicationContext, file, knownLength) }
            }
            if (!keepGoing()) throw CancellationException("download cancelled after verification")
            PolarisErrorReport.record("download-validated bytes=${file.length()} elapsedMs=${SystemClock.elapsedRealtime()-started} check=$evidence wholeFileDecoded=false")
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw PolarisVerificationTimeout()
        }
    }
    private suspend fun probe(context: Context, file: File, known: Boolean): String {
        var remote: Messenger? = null
        var bound = false
        var finished = false
        var linked: IBinder? = null
        val gone = CompletableDeferred<Unit>()
        val death = IBinder.DeathRecipient { gone.complete(Unit) }
        lateinit var connection: ServiceConnection
        fun unbind() { if (bound) { bound = false; runCatching { context.unbindService(connection) } } }
        try { return suspendCancellableCoroutine { continuation ->
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            finished = true
            if (continuation.isActive) {
                val failure = message.data.getString("error")
                if (failure != null) continuation.resumeWithException(IOException("media probe: $failure"))
                else continuation.resume(message.data.getString("ok") ?: "probe")
            }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                if (!continuation.isActive) {
                    runCatching { remote?.send(Message.obtain(null, PolarisProbeService.CANCEL)) }; return
                }
                try {
                    binder.linkToDeath(death, 0)
                    linked = binder
                    remote!!.send(Message.obtain(null, PolarisProbeService.CHECK).apply {
                    replyTo = response
                    data = Bundle().apply { putString("path", file.absolutePath); putBoolean("known", known) }
                }) } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("probe connection failed", error))
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                gone.complete(Unit)
                if (continuation.isActive) continuation.resumeWithException(PolarisVerificationTimeout())
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        bound = context.bindService(Intent(context, PolarisProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound && continuation.isActive) continuation.resumeWithException(IOException("cannot start media probe"))
        } } finally {
            // Queue cancellation does not finish until the old native reader has stopped.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (!finished && remote != null) {
                runCatching { remote?.send(Message.obtain(null, PolarisProbeService.CANCEL)) }
                    withTimeoutOrNull(PolarisDownloadPolicy.PROBE_MS + 1_000L) { gone.await() }
                }
                linked?.let { runCatching { it.unlinkToDeath(death, 0) } }
                unbind()
            }
        }
    }
    fun commit(part: File, target: File, keepGoing: () -> Boolean) {
        if (!keepGoing()) throw CancellationException("download cancelled before commit")
        Os.rename(part.absolutePath, target.absolutePath)
    }
}
