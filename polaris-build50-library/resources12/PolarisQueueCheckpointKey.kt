package app.hush.music.polaris

/** Small checkpoint key; unchanged position ticks do not traverse/serialize the playlist. */
class PolarisQueueCheckpointKey(
    val generation: Long,
    val revision: Long,
    val index: Int,
    val mediaId: String?,
    val title: String?,
    val continuation: Any,
) {
    fun matches(other: PolarisQueueCheckpointKey?): Boolean = other != null &&
        generation == other.generation && revision == other.revision && index == other.index &&
        mediaId == other.mediaId && title == other.title && continuation === other.continuation
}
