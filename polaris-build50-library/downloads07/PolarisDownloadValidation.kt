package app.hush.music.polaris

import android.media.MediaExtractor
import android.media.MediaFormat
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException

object PolarisDownloadValidation {
    /** Parse every compressed sample. This checks the container, not every decoded PCM frame. */
    fun validate(file: File, keepGoing: () -> Boolean) {
        PolarisDownloadIntegrity.rejectNonAudio(file)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("download has no audio track")
            val format = extractor.getTrackFormat(audio)
            extractor.selectTrack(audio)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            var samples = 0L
            var lastTimeUs = 0L
            while (true) {
                if (!keepGoing()) throw CancellationException("download validation cancelled")
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (size == 0) throw IOException("empty audio sample")
                samples++
                lastTimeUs = maxOf(lastTimeUs, extractor.sampleTime)
                if (!extractor.advance()) break
            }
            if (samples == 0L) throw IOException("download has no readable audio samples")
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (duration > 0 && lastTimeUs < duration - maxOf(5_000_000L, duration / 10)) {
                throw IOException("audio ends early durationUs=$duration lastSampleUs=$lastTimeUs")
            }
            PolarisErrorReport.record("download-validated bytes=${file.length()} samples=$samples durationUs=$duration lastUs=$lastTimeUs check=container-samples")
        } finally { extractor.release() }
    }
    fun commit(part: File, target: File, keepGoing: () -> Boolean) {
        if (!keepGoing()) throw CancellationException("download cancelled before commit")
        // Same-directory rename atomically replaces an old file; no delete-before-copy gap.
        Os.rename(part.absolutePath, target.absolutePath)
    }
}
