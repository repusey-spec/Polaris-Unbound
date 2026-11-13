package app.hush.music.polaris

/** Missing remote rows are not proof of an unlike or of a complete, same-channel snapshot. */
object PolarisLikedSnapshotPolicy {
    fun likedAfterSnapshot(localLiked: Boolean, remotePresent: Boolean, localIntent: Boolean?): Boolean =
        localIntent ?: (localLiked || remotePresent)
    fun <T> likedDate(existing: T?, incoming: T): T = existing ?: incoming
}
