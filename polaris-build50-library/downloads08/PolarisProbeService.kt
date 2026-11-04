package app.hush.music.polaris

import android.app.Service
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Private process: even a blocked native extractor cannot hold the download worker forever. */
class PolarisProbeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == CANCEL) { Process.killProcess(Process.myPid()); true }
        else if (msg.what == CHECK && !busy) {
            busy = true
            val file = msg.data.getString("path").orEmpty()
            val known = msg.data.getBoolean("known")
            val reply = msg.replyTo
            // This watchdog runs on the service main thread, separately from the native read.
            val watchdog = Runnable { Process.killProcess(Process.myPid()) }
            main.postDelayed(watchdog, PolarisDownloadPolicy.PROBE_MS)
            Thread {
                val result = Bundle()
                try { result.putString("ok", probe(File(file), known)) }
                catch (error: Exception) { result.putString("error", error.message ?: error.javaClass.simpleName) }
                main.post {
                    main.removeCallbacks(watchdog)
                    runCatching { reply.send(Message.obtain(null, RESULT).apply { data = result }) }
                    busy = false
                    stopSelf()
                }
            }.start()
            true
        } else true
    })
    override fun onBind(intent: Intent?): IBinder = endpoint.binder

    private fun probe(file: File, known: Boolean): String {
        PolarisDownloadIntegrity.rejectNonAudio(file)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("download has no audio track")
            val format = extractor.getTrackFormat(audio)
            extractor.selectTrack(audio)
            // A direct bounded buffer avoids copying a 4 MiB Java buffer for every sample.
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            var readCount = 0
            fun read(limit: Int): Long {
                var last = -1L
                repeat(limit) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) return last
                    if (size == 0 || size > buffer.capacity()) throw IOException("invalid audio sample")
                    readCount++
                    last = maxOf(last, extractor.sampleTime)
                    if (!extractor.advance()) return last
                }
                return last
            }
            if (read(PolarisDownloadPolicy.PROBE_SAMPLES) < 0) throw IOException("no readable audio samples")
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            var tailChecked = false
            if (duration > 0) {
                extractor.seekTo((duration - 500_000L).coerceAtLeast(0), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val last = read(PolarisDownloadPolicy.PROBE_SAMPLES)
                tailChecked = last >= duration - maxOf(2_000_000L, duration / 100)
                if (!tailChecked) throw IOException("audio tail missing durationUs=$duration lastUs=$last")
            } else if (!known) throw IOException("unknown length and duration; completeness unverified")
            return "bounded-head-tail samples=$readCount durationUs=$duration tail=$tailChecked knownLength=$known"
        } finally { extractor.release() }
    }
    companion object { const val CHECK = 1; const val RESULT = 2; const val CANCEL = 3 }
}
