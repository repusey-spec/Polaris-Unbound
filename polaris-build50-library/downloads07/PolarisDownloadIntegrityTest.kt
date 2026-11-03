package app.hush.music.polaris

import java.io.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PolarisDownloadIntegrityTest {
    private fun copy(bytes: ByteArray, expected: Long): ByteArray {
        val input = ByteArrayInputStream(bytes)
        val output = ByteArrayOutputStream()
        PolarisDownloadIntegrity.copy({ input.read(it) }, output, expected, { true }, { _, _ -> })
        return output.toByteArray()
    }
    @Test fun completeLengthIsAccepted() { assertArrayEquals(ByteArray(300001) { 42 }, copy(ByteArray(300001) { 42 }, 300001)) }
    @Test fun truncatedNonzeroFileIsRejected() {
        try { copy(ByteArray(700), 1000); fail("must not commit a 700-byte prefix") }
        catch (e: PolarisIncompleteDownload) { assertEquals(1000L, e.expected); assertEquals(700L, e.actual) }
    }
    @Test fun extraBytesAreRejectedInsteadOfMixingAnotherResponse() {
        try { copy(ByteArray(1001), 1000); fail("length overrun") }
        catch (e: PolarisIncompleteDownload) { assertEquals(1001L, e.actual) }
    }
    @Test fun zeroBytesAreRejectedEvenWithUnknownLength() {
        try { copy(byteArrayOf(), -1); fail("empty file") } catch (e: IOException) { assertEquals("empty download", e.message) }
    }
    @Test fun unknownLengthIsNotInventedAndNeedsLaterMediaValidation() {
        var expectedReported = 0L
        val input = ByteArrayInputStream(ByteArray(900))
        assertEquals(900L, PolarisDownloadIntegrity.copy({ input.read(it) }, ByteArrayOutputStream(), -1, { true }, { _, n -> expectedReported = n }))
        assertEquals(-1L, expectedReported)
    }
    @Test fun readFailureIsPreservedAfterSomeBytes() {
        val original = IOException("socket disconnected")
        var calls = 0
        try {
            PolarisDownloadIntegrity.copy({ if (calls++ == 0) 4 else throw original }, ByteArrayOutputStream(), 10, { true }, { _, _ -> })
            fail("read error cannot become EOF")
        } catch (e: IOException) { assertSame(original, e) }
    }
    @Test fun cancellationBeforeCommitDoesNotBecomeCompletion() {
        var allowed = true
        try {
            PolarisDownloadIntegrity.copy({ allowed = false; 2 }, ByteArrayOutputStream(), 2, { allowed }, { _, _ -> })
            fail("cancelled transfer")
        } catch (_: CancellationException) { }
    }
    @Test fun repeatedZeroByteReadsCannotHangTheWorker() {
        try { PolarisDownloadIntegrity.copy({ 0 }, ByteArrayOutputStream(), 10, { true }, { _, _ -> }); fail("stalled reader") }
        catch (e: IOException) { assertTrue(e.message!!.contains("no progress")) }
    }
    @Test fun nonAudioResponsesAreRejected() {
        for (text in listOf("<html>blocked</html>", "{\"error\":403}", "#EXTM3U\npreview")) {
            val file = File.createTempFile("polaris-nonaudio", ".part")
            try { file.writeText(text); PolarisDownloadIntegrity.rejectNonAudio(file); fail("not audio") }
            catch (e: IOException) { assertTrue(e.message!!.contains("instead of audio")) }
            finally { file.delete() }
        }
    }
}
