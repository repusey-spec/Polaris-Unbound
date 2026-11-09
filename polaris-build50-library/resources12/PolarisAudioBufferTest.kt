package app.hush.music.polaris

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.BaseAudioProcessor
import app.hush.music.eq.audio.CustomEqualizerAudioProcessor
import app.hush.music.eq.data.ParametricEQ
import app.hush.music.eq.data.ParametricEQBand
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PolarisAudioBufferTest {
    private val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
    private fun pcm(vararg samples: Int): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
        .order(ByteOrder.nativeOrder()).apply { samples.forEach { putShort(it.toShort()) }; flip() }
    private fun pipeline(eq: CustomEqualizerAudioProcessor, vararg more: AudioProcessor): AudioProcessingPipeline =
        AudioProcessingPipeline(ImmutableList.copyOf(listOf<AudioProcessor>(eq) + more)).apply { configure(format); flush() }
    private fun drain(pipeline: AudioProcessingPipeline): List<Int> {
        val result = mutableListOf<Int>()
        while (true) {
            val output = pipeline.output
            if (!output.hasRemaining()) break
            while (output.hasRemaining()) result += output.short.toInt()
        }
        return result
    }
    @Test fun disabledEqUsesOriginalBufferAndPartialDrainPreservesOwnership() {
        val eq = CustomEqualizerAudioProcessor(); val pipeline = pipeline(eq)
        val input = pcm(100, -100, 200, -200)
        pipeline.queueInput(input)
        val output = pipeline.output
        assertSame(input, output)
        assertEquals(0, input.position())
        assertEquals(100, output.short.toInt())
        assertEquals(2, input.position())
        assertSame(output, pipeline.output)
        assertEquals(listOf(-100, 200, -200), drain(pipeline))
        assertFalse(input.hasRemaining())
    }
    @Test fun disabledEqChainsIntoAnotherActiveMedia3ProcessorWithoutLostSamples() {
        val copy = object : BaseAudioProcessor() {
            override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat
            override fun queueInput(inputBuffer: ByteBuffer) {
                if (!inputBuffer.hasRemaining()) return
                replaceOutputBuffer(inputBuffer.remaining()).apply { put(inputBuffer); flip() }
            }
        }
        val pipeline = pipeline(CustomEqualizerAudioProcessor(), copy)
        repeat(100) { i ->
            val input = pcm(i, -i, i + 1, -i - 1)
            pipeline.queueInput(input)
            assertEquals(listOf(i, -i, i + 1, -i - 1), drain(pipeline))
            assertFalse(input.hasRemaining())
        }
    }
    @Test fun enabledEqReusesSameNativeAllocationOverTenThousandBuffers() {
        val eq = CustomEqualizerAudioProcessor()
        eq.applyProfile(ParametricEQ(0.0, listOf(ParametricEQBand(1000.0, 0.0))))
        eq.configure(format); eq.flush()
        eq.queueInput(pcm(100, -100, 200, -200)); val first = eq.getOutput()
        first.position(first.limit())
        repeat(10_000) {
            eq.queueInput(pcm(100, -100, 200, -200))
            val output = eq.getOutput()
            assertSame(first, output)
            assertEquals(100, output.short.toInt())
            output.position(output.limit())
        }
    }
    @Test fun nativeBufferOnlyGrowsAndResetReleasesReusableStorage() {
        val eq = CustomEqualizerAudioProcessor(); eq.applyProfile(ParametricEQ(-6.0, emptyList()))
        eq.configure(format); eq.flush()
        eq.queueInput(pcm(100, -100)); val small = eq.getOutput(); small.position(small.limit())
        eq.queueInput(pcm(100, -100, 200, -200)); val large = eq.getOutput()
        assertNotSame(small, large); large.position(large.limit())
        eq.queueInput(pcm(100, -100)); val reused = eq.getOutput(); assertSame(large, reused); reused.position(reused.limit())
        eq.reset(); eq.configure(format); eq.flush(); eq.queueInput(pcm(100, -100))
        assertNotSame(large, eq.getOutput())
    }
    @Test fun runtimeEnableDisableDoesNotLoseOrDuplicatePcm() {
        val eq = CustomEqualizerAudioProcessor(); val pipeline = pipeline(eq)
        repeat(100) {
            eq.disable(); pipeline.queueInput(pcm(1000, -1000)); assertEquals(listOf(1000, -1000), drain(pipeline))
            eq.applyProfile(ParametricEQ(-6.020599913279624, emptyList()))
            pipeline.queueInput(pcm(1000, -1000))
            val output = drain(pipeline)
            assertEquals(2, output.size); assertEquals(500.0, output[0].toDouble(), 1.0); assertEquals(-500.0, output[1].toDouble(), 1.0)
        }
    }
    @Test fun endOfStreamWaitsForPendingOutputAndFlushAcceptsNextTrack() {
        val pipeline = pipeline(CustomEqualizerAudioProcessor())
        pipeline.queueInput(pcm(1, 2)); pipeline.queueEndOfStream()
        assertFalse(pipeline.isEnded); assertEquals(listOf(1, 2), drain(pipeline)); assertTrue(pipeline.isEnded)
        pipeline.configure(format); pipeline.flush(); pipeline.queueInput(pcm(3, 4))
        assertEquals(listOf(3, 4), drain(pipeline))
    }
    @Test fun concurrentUiProfileChangesDoNotMutateAudioThreadFilters() {
        val eq = CustomEqualizerAudioProcessor(); val pipeline = pipeline(eq)
        val worker = Thread { repeat(1000) { eq.applyProfile(ParametricEQ(-3.0, listOf(ParametricEQBand(1000.0, (it % 3).toDouble())))); eq.disable() } }
        worker.start()
        repeat(1000) { pipeline.queueInput(pcm(100, -100)); assertEquals(2, drain(pipeline).size) }
        worker.join(); eq.disable(); pipeline.queueInput(pcm(321, -321)); assertEquals(listOf(321, -321), drain(pipeline))
    }
    @Test fun monoPcmAndPreampOnlyProfileAreHandled() {
        val eq = CustomEqualizerAudioProcessor(); eq.applyProfile(ParametricEQ(-6.020599913279624, emptyList()))
        eq.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT)); eq.flush()
        eq.queueInput(pcm(1000, -1000)); val output = eq.getOutput()
        assertEquals(500.0, output.short.toDouble(), 1.0); assertEquals(-500.0, output.short.toDouble(), 1.0)
    }
}
