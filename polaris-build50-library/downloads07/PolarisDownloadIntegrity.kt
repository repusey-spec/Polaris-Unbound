package app.hush.music.polaris

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

class PolarisIncompleteDownload(val expected: Long, val actual: Long) :
    IOException("download length mismatch expected=$expected actual=$actual")

/** Byte-level checks used by the real transfer, not a simulation-only implementation. */
object PolarisDownloadIntegrity {
    fun copy(
        read: (ByteArray) -> Int,
        output: OutputStream,
        expected: Long,
        keepGoing: () -> Boolean,
        progress: (Long, Long) -> Unit,
    ): Long {
        val buffer = ByteArray(128 * 1024)
        var written = 0L
        var emptyReads = 0
        while (true) {
            if (!keepGoing()) throw CancellationException("download cancelled")
            val count = read(buffer) // IO exceptions must reach the report and never become EOF.
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > 8) throw IOException("download source made no progress")
                continue
            }
            emptyReads = 0
            output.write(buffer, 0, count)
            written += count
            if (expected > 0 && written > expected) throw PolarisIncompleteDownload(expected, written)
            progress(written, expected)
        }
        if (!keepGoing()) throw CancellationException("download cancelled before validation")
        if (written == 0L) throw IOException("empty download")
        if (expected > 0 && written != expected) throw PolarisIncompleteDownload(expected, written)
        return written
    }
    fun rejectNonAudio(file: File) {
        val prefix = file.inputStream().use { input ->
            val bytes = ByteArray(512)
            val n = input.read(bytes)
            if (n <= 0) throw IOException("empty download")
            String(bytes, 0, n, Charsets.US_ASCII).trimStart().lowercase()
        }
        if (prefix.startsWith("<") || prefix.startsWith("{") || prefix.startsWith("[") ||
            prefix.startsWith("#extm3u")) throw IOException("download is text/manifest instead of audio")
    }
}
