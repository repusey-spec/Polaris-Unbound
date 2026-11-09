package app.hush.music.polaris

import java.util.concurrent.atomic.AtomicLong

/** Counters only on slow paths. Never emits a log or allocates a record per PCM buffer. */
object PolarisPlaybackWorkMetrics {
    private val pcmAllocations = AtomicLong()
    private val pcmAllocatedBytes = AtomicLong()
    private val artworkRuns = AtomicLong()
    private val artworkMaxMs = AtomicLong()
    private val queueWrites = AtomicLong()
    private val stateWrites = AtomicLong()
    private val checkpointMaxMs = AtomicLong()

    fun pcmAllocated(bytes: Int) { pcmAllocations.incrementAndGet(); pcmAllocatedBytes.addAndGet(bytes.toLong()) }
    fun artworkCompleted(ms: Long) { artworkRuns.incrementAndGet(); maximum(artworkMaxMs, ms) }
    fun checkpointCompleted(fullQueue: Boolean, ms: Long) {
        if (fullQueue) queueWrites.incrementAndGet()
        stateWrites.incrementAndGet(); maximum(checkpointMaxMs, ms)
    }
    private fun maximum(counter: AtomicLong, value: Long) {
        var old = counter.get()
        while (value > old && !counter.compareAndSet(old, value)) old = counter.get()
    }
    fun summary(): String = "pcmAllocations=${pcmAllocations.get()} pcmAllocatedBytes=${pcmAllocatedBytes.get()} artworkRuns=${artworkRuns.get()} artworkMaxMs=${artworkMaxMs.get()} queueWrites=${queueWrites.get()} stateWrites=${stateWrites.get()} checkpointMaxMs=${checkpointMaxMs.get()}"
}
