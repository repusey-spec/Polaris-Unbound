package app.hush.music.polaris

/** UI navigation only. Never change a Player timeline to display another source. */
object PolarisCarNavigation {
    const val RADIO = "polaris:radio"
    const val STREAMING = "polaris:streaming"
    const val TOKEN_COMMAND = "app.hush.music.polaris.CAR_PLATFORM_TOKEN"
    const val TOKEN_KEY = "platform-token"
    const val SHOW_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    fun isRadio(mediaId: String?) = mediaId?.startsWith("polaris:station:") == true
    fun opposite(mediaId: String?) = if (isRadio(mediaId)) STREAMING else RADIO
    fun title(parent: String) = if (parent == RADIO) "라디오 채널" else "스트리밍 보관함"
}
