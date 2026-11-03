package app.hush.music.polaris

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import app.hush.music.playback.ChunkingDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PolarisChunkingDataSourceTest {
    private class Source(val full: Int = 11, val known: Boolean = true) : DataSource {
        var position = 0; var end = 0; var failureAt = -1; var eofAt = -1
        var range416Total: Int? = null
        var changeTag = false
        val original = IOException("network read failed")
        val starts = mutableListOf<Long>()
        override fun addTransferListener(listener: TransferListener) { }
        override fun open(spec: DataSpec): Long {
            if (spec.position >= full && range416Total != null) throw HttpDataSource.InvalidResponseCodeException(
                416, "Range", null, mapOf("Content-Range" to listOf("bytes */$range416Total")), spec, byteArrayOf())
            position = spec.position.toInt(); end = minOf(full, position + spec.length.toInt())
            starts += spec.position
            return if (known) (end - position).toLong() else C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failureAt >= 0 && position >= failureAt) throw original
            if (eofAt >= 0 && position >= eofAt) return C.RESULT_END_OF_INPUT
            if (position >= end) return C.RESULT_END_OF_INPUT
            buffer[offset] = position.toByte(); position++; return 1
        }
        override fun getResponseHeaders(): Map<String, List<String>> = (if (known)
            mapOf("Content-Range" to listOf("bytes ${starts.last()}-${end - 1}/$full")) else emptyMap()) +
            (if (changeTag) mapOf("ETag" to listOf(if (starts.size > 1) "changed" else "original")) else emptyMap())
        override fun getUri(): Uri = Uri.parse("https://example.invalid/audio")
        override fun close() { }
    }
    private fun request() = DataSpec.Builder().setUri("https://example.invalid/audio").setKey("song").build()
    private fun drain(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(3)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    @Test fun chunkBoundariesDoNotTruncateOrDuplicateBytes() {
        val upstream = Source(); val source = ChunkingDataSource(upstream, 4)
        assertEquals(11L, source.open(request()))
        assertArrayEquals(ByteArray(11) { it.toByte() }, drain(source))
        assertEquals(listOf(0L, 4L, 8L), upstream.starts)
    }
    @Test fun readExceptionAfterOneCompletedChunkRemainsTheOriginalException() {
        val upstream = Source().apply { failureAt = 5 }; val source = ChunkingDataSource(upstream, 4)
        source.open(request())
        try { drain(source); fail("must not turn network failure into EOF") }
        catch (error: IOException) { assertSame(upstream.original, error) }
    }
    @Test fun earlyEofWithKnownTotalIsAReportedFailure() {
        val source = ChunkingDataSource(Source().apply { eofAt = 5 }, 4); source.open(request())
        try { drain(source); fail("missing six bytes") }
        catch (error: EOFException) { assertTrue(error.message!!.contains("expected=11 actual=5")) }
    }
    @Test fun unknownLengthRemainsUnknownAndCanReachCleanEof() {
        val source = ChunkingDataSource(Source(full = 7, known = false), 4)
        assertEquals(-1L, source.open(request()))
        assertArrayEquals(ByteArray(7) { it.toByte() }, drain(source))
    }
    @Test fun explicitRequestLengthLimitsTheTransferredRange() {
        val source = ChunkingDataSource(Source(), 4)
        source.open(request().buildUpon().setPosition(3).setLength(5).build())
        assertArrayEquals(ByteArray(5) { (it + 3).toByte() }, drain(source))
    }
    @Test fun explicit416TotalCanProveTheEndOfAnUnknownLengthResponse() {
        val source = ChunkingDataSource(Source(full = 4, known = false).apply { range416Total = 4 }, 4)
        assertEquals(-1L, source.open(request()))
        assertArrayEquals(ByteArray(4) { it.toByte() }, drain(source))
    }
    @Test fun unproven416CannotHideMissingBytes() {
        val source = ChunkingDataSource(Source(full = 4, known = false).apply { range416Total = 9 }, 4)
        source.open(request())
        try { drain(source); fail("still missing bytes") }
        catch (error: HttpDataSource.InvalidResponseCodeException) { assertEquals(416, error.responseCode) }
    }
    @Test fun aChangedEntityIsNotConcatenatedIntoTheSameFile() {
        val source = ChunkingDataSource(Source().apply { changeTag = true }, 4)
        source.open(request())
        try { drain(source); fail("entity changed") }
        catch (error: IOException) { assertTrue(error.message!!.contains("entity changed")) }
    }
    @Test fun originalReadFailureIsVisibleInTheExistingExportableReport() {
        val context = RuntimeEnvironment.getApplication()
        PolarisErrorReport.install(context)
        val source = ChunkingDataSource(Source().apply { failureAt = 5 }, 4)
        source.open(request()); try { drain(source) } catch (_: IOException) { }
        val report = PolarisErrorReport.dump(context)
        assertTrue(report.contains("download-read id=song offset=5 expected=11"))
        assertTrue(report.contains("network read failed"))
    }
}
