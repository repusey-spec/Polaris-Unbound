package app.hush.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import app.hush.music.polaris.PolarisErrorReport
import java.io.EOFException
import java.io.IOException

/** Echo Music chunking integration; Polaris 50.7 preserves errors and verifies range boundaries. */
class ChunkingDataSource(private val upstream: DataSource, private val chunkSize: Long) : DataSource {
    private var spec: DataSpec? = null
    private var total = C.LENGTH_UNSET.toLong()
    private var read = 0L
    private var remainingChunk = 0L
    private var opened = false
    private var sourceLength = -1L
    private var entityTag: String? = null
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        total = dataSpec.length
        read = 0
        sourceLength = -1
        entityTag = null
        opened = true
        openChunk()
        return total
    }
    private fun openChunk() {
        val request = spec ?: throw IOException("missing download data spec")
        val count = if (total >= 0) minOf(chunkSize, total - read) else chunkSize
        if (count <= 0) { remainingChunk = 0; return }
        try {
            val length = upstream.open(request.buildUpon().setPosition(request.position + read).setLength(count).build())
            remainingChunk = if (length >= 0) minOf(length, count) else count
            val range = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                ?.value?.firstOrNull()
            val tag = upstream.responseHeaders.entries.firstOrNull { it.key.equals("ETag", true) }?.value?.firstOrNull()
            if (entityTag != null && tag != null && entityTag != tag) throw IOException("download entity changed between chunks")
            if (entityTag == null) entityTag = tag
            val parsed = range?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it.trim()) }
            if (parsed != null) {
                val start = parsed.groupValues[1].toLong()
                val end = parsed.groupValues[2].toLong()
                val full = parsed.groupValues[3].toLong()
                if (start != request.position + read || end < start || full <= end) throw IOException("invalid download range")
                if (sourceLength >= 0 && sourceLength != full) throw IOException("download total changed between chunks")
                sourceLength = full
                remainingChunk = minOf(remainingChunk, end - start + 1)
                if (total < 0) total = full - request.position
                if (read + remainingChunk > total) throw IOException("download range exceeds expected end")
            } else if (total < 0 && length in 0 until count) {
                total = read + length
            }
        } catch (error: InvalidResponseCodeException) {
            val end = error.headerFields.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                ?.let { Regex("bytes \\*/(\\d+)").matchEntire(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()
            // A server's explicit unsatisfied-range total can prove a clean EOF even
            // when the initial response had no length. An unproven 416 stays an error.
            if (error.responseCode == 416 && (total >= 0 && read == total || total < 0 && end == request.position + read)) {
                total = read
                remainingChunk = 0
            }
            else { log(error); throw error }
        } catch (error: Exception) { log(error); throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened || total >= 0 && read >= total) return C.RESULT_END_OF_INPUT
        if (remainingChunk == 0L) { upstream.close(); openChunk() }
        if (remainingChunk == 0L) return C.RESULT_END_OF_INPUT
        val n = try { upstream.read(buffer, offset, minOf(length.toLong(), remainingChunk).toInt()) }
        catch (error: Exception) { log(error); throw error }
        if (n == C.RESULT_END_OF_INPUT) {
            if (total >= 0 && read < total) {
                val error = EOFException("download ended early expected=$total actual=$read")
                log(error)
                throw error
            }
            return n
        }
        if (n > 0) { read += n; remainingChunk -= n }
        return n
    }
    private fun log(error: Exception) {
        PolarisErrorReport.failure("download-read id=${spec?.key ?: "none"} offset=$read expected=$total", error)
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { opened = false; upstream.close() }
}
class ChunkingDataSourceFactory(private val upstreamFactory: DataSource.Factory, private val chunkSize: Long = 5L * 1024 * 1024) : DataSource.Factory {
    override fun createDataSource(): DataSource = ChunkingDataSource(upstreamFactory.createDataSource(), chunkSize)
}
