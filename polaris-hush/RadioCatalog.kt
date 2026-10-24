package app.hush.music.polaris

/**
 * Polaris Radio catalog for integration into the original Hush app.
 * Keep the original Hush playback and Android Auto MediaLibrarySession.
 * IDs and names are taken from Polaris RadioPresets.java.
 * Streaming URLs are NOT stored here: Polaris uses station-specific live URL
 * resolution and fallback logic; copying resolver endpoints as direct media
 * URLs would break several Korean stations.
 */
object RadioCatalog {
    data class Station(val id: String, val title: String, val region: String)
    val stations = listOf(
        Station("kr1", "89.1 KBS CoolFM", "KR"),
        Station("kr2", "91.9 MBC FM4U", "KR"),
        Station("kr3", "93.9 CBS MusicFM", "KR"),
        Station("kr4", "95.9 MBC 표준FM", "KR"),
        Station("kr5", "102.7 AFN EagleFM", "KR"),
        Station("kr6", "107.7 SBS PowerFM", "KR"),
        Station("gallery", "Jazz from Gallery 41", "SF"),
        Station("wild949", "Wild 94.9", "SF"),
        Station("z100", "Z100 New York", "NY"),
        Station("power1051", "Power 105.1", "NY"),
        Station("kiis", "102.7 KIIS-FM", "LA"),
        Station("alt987", "ALT 98.7", "LA"),
        Station("myfm1043", "104.3 MYfm", "LA"),
    )
    fun find(id: String): Station? = stations.firstOrNull { it.id == id }
    const val RADIO_ROOT_ID = "polaris:radio"
    const val STREAMING_ROOT_ID = "polaris:streaming"
    // CAN is intentionally not exposed in the UI yet.
    const val FUTURE_CAN_ROOT_ID = "polaris:can"
}
