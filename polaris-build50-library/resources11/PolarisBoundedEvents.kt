package app.hush.music.polaris

/** Bounded producer queue: no executor task or disk write per playback event. */
class PolarisBoundedEvents(private val maxChars: Int = 64_000, private val maxEntries: Int = 512) {
    private val lines = ArrayDeque<String>()
    private var chars = 0
    private var dropped = 0
    @Synchronized fun add(event: String) {
        val line = event.take(minOf(4_000, maxChars))
        while (lines.isNotEmpty() && (chars + line.length > maxChars || lines.size >= maxEntries)) {
            chars -= lines.removeFirst().length
            dropped++
        }
        lines.addLast(line); chars += line.length
    }
    @Synchronized fun drain(): String {
        val prefix = if (dropped > 0) "diagnostics dropped=$dropped (bounded queue)\n" else ""
        val result = prefix + lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
        lines.clear(); chars = 0; dropped = 0
        return result
    }
    @Synchronized fun snapshot(): String = lines.joinToString("\n")
    @Synchronized fun retainedChars(): Int = chars
    @Synchronized fun retainedEntries(): Int = lines.size
}
