package app.hush.music.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.hush.music.eq.data.FilterType
import app.hush.music.eq.data.ParametricEQ
import app.hush.music.eq.data.ParametricEQBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Custom audio processor for ExoPlayer that applies parametric EQ using biquad filters.
 * Supports 16-bit PCM stereo/mono audio.
 */
@UnstableApi
class CustomEqualizerAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var isActive = false
    private var equalizerEnabled = false

    // Keep the reusable allocation separate from the pending output reference.
    private var workingBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var filters: Array<BiquadFilter> = emptyArray()
    private var preampGain: Double = 1.0
    // UI threads publish immutable settings; filter state is touched only by the audio thread.
    private class Settings(val profile: ParametricEQ?)
    @Volatile private var requestedSettings = Settings(null)
    private var appliedSettings: Settings? = null

    companion object {
        private const val TAG = "CustomEqualizerAP"
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    fun applyProfile(parametricEQ: ParametricEQ) {
        requestedSettings = Settings(parametricEQ.copy(bands = parametricEQ.bands.toList()))
    }

    fun disable() { requestedSettings = Settings(null) }
    fun isEnabled(): Boolean = requestedSettings.profile != null

    private fun applyRequestedSettings() {
        val settings = requestedSettings
        if (appliedSettings === settings || sampleRate <= 0) return
        val profile = settings.profile
        if (profile == null) {
            filters = emptyArray()
            preampGain = 1.0
            equalizerEnabled = false
        } else {
            preampGain = 10.0.pow(profile.preamp / 20.0)
            val bands = profile.bands.filter { it.enabled && it.frequency < sampleRate / 2.0 }
            // Preserve history for gain-only edits, but update coefficients on the audio thread.
            if (filters.size == bands.size && filters.indices.all {
                    filters[it].frequency == bands[it].frequency && filters[it].filterType == bands[it].filterType && filters[it].q == bands[it].q
                }) {
                for (i in filters.indices) filters[i].updateGain(bands[i].gain)
            } else {
                createFilters(bands)
            }
            equalizerEnabled = true
        }
        appliedSettings = settings
    }

    private fun createFilters(bands: List<ParametricEQBand>) {
        if (sampleRate == 0) return
        filters = bands.map { band ->
            BiquadFilter(
                sampleRate = sampleRate,
                frequency = band.frequency,
                gain = band.gain,
                q = band.q,
                filterType = band.filterType,
            )
        }.toTypedArray()
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        appliedSettings = null
        filters = emptyArray()
        applyRequestedSettings()

        if (encoding != C.ENCODING_PCM_16BIT || channelCount > 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        isActive = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        applyRequestedSettings()
        if (!equalizerEnabled || (filters.isEmpty() && preampGain == 1.0)) {
            // Media3 keeps ownership until downstream drains this same buffer. Do not
            // advance it eagerly: that would let the codec recycle still-pending PCM.
            outputBuffer = inputBuffer
            return
        }

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        ensureOutputCapacity(inputSize)

        if (encoding == C.ENCODING_PCM_16BIT) {
            processAudioBuffer16Bit(inputBuffer, outputBuffer)
        } else {
            outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun ensureOutputCapacity(size: Int) {
        if (workingBuffer.capacity() < size) {
            workingBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            app.hush.music.polaris.PolarisPlaybackWorkMetrics.pcmAllocated(size)
        }
        workingBuffer.clear()
        outputBuffer = workingBuffer
    }

    private fun processAudioBuffer16Bit(input: ByteBuffer, output: ByteBuffer) {
        repeat(input.remaining() / 2 / channelCount) {
            when (channelCount) {
                1 -> {
                    val sample = input.getShort().toDouble() / 32768.0
                    var processed = sample
                    for (filter in filters) processed = filter.processSample(processed)
                    processed *= preampGain
                    output.putShort((processed * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
                2 -> {
                    val leftSample = input.getShort().toDouble() / 32768.0
                    val rightSample = input.getShort().toDouble() / 32768.0

                    var left = leftSample
                    var right = rightSample
                    for (filter in filters) {
                        filter.processStereo(left, right)
                        left = filter.lastOutputLeft
                        right = filter.lastOutputRight
                    }
                    left *= preampGain
                    right *= preampGain

                    output.putShort((left * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                    output.putShort((right * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
                else -> {
                    repeat(channelCount) { output.putShort(input.getShort()) }
                }
            }
        }
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer.remaining() == 0

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        filters.forEach { it.reset() }
    }

    override fun reset() {
        flush()
        workingBuffer = EMPTY_BUFFER
        appliedSettings = null
        filters = emptyArray()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        isActive = false
        filters.forEach { it.reset() }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }
}
