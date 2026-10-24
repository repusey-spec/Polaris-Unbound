package app.hush.music.polaris

/**
 * Direct audio endpoints from PolarisMediaService.java.
 * Only direct streams included. Korean API/JSON resolver stations need a
 * separate port of CurrentProgramResolver and playback URL resolution.
 */
object PolarisRadioStreams {
    data class Stream(val id: String, val title: String, val url: String)
    val streams = listOf(
        Stream("kr3", "93.9 CBS MusicFM", "http://m-aac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8"),
        Stream("kr5", "102.7 AFN EagleFM", "https://playerservices.streamtheworld.com/api/livestream-redirect/AFNP_DGU_SC"),
        Stream("gallery", "Jazz from Gallery 41", "https://streaming.live365.com/a94394"),
        Stream("wild949", "Wild 94.9", "https://stream.revma.ihrhls.com/zc305"),
        Stream("z100", "Z100 New York", "https://stream.revma.ihrhls.com/zc1469"),
        Stream("power1051", "Power 105.1", "https://stream.revma.ihrhls.com/zc1481"),
        Stream("kiis", "102.7 KIIS-FM", "https://stream.revma.ihrhls.com/zc185"),
        Stream("alt987", "ALT 98.7", "https://stream.revma.ihrhls.com/zc201"),
        Stream("myfm1043", "104.3 MYfm", "https://stream.revma.ihrhls.com/zc173"),
    )
}
