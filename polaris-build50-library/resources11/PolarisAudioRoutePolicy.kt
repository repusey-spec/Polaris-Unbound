package app.hush.music.polaris

object PolarisAudioRoutePolicy {
    /** Device inventory changes alone do not justify seeking healthy playback. */
    fun needsSeek(isPlaying: Boolean, playWhenReady: Boolean, buffering: Boolean, idle: Boolean): Boolean =
        !isPlaying && playWhenReady && buffering && !idle
}
