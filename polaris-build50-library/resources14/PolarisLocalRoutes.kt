package app.hush.music.polaris

/** A prefetched neighbour must not overwrite the current track's local-source evidence. */
internal class PolarisLocalRoutes {
    private val files = LinkedHashSet<String>()
    @Synchronized fun openedFile(id: String) {
        files.remove(id)
        if (files.size >= 64) files.remove(files.first())
        files.add(id)
    }
    @Synchronized fun openedRemote(id: String) { files.remove(id) }
    @Synchronized fun wasFile(id: String): Boolean = id in files
}
