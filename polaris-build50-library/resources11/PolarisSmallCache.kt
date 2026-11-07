package app.hush.music.polaris

/** Service-local metadata cache, bounded even when a long AA session visits many playlists. */
class PolarisSmallCache<T>(private val limit: Int = 512) {
    private val values = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>): Boolean = size > limit
    }
    @Synchronized operator fun get(key: String): T? = values[key]
    @Synchronized operator fun set(key: String, value: T) { values[key] = value }
    @Synchronized fun size(): Int = values.size
}
