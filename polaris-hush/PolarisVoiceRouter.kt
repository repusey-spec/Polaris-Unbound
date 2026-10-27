package app.hush.music.polaris

import java.util.Locale

/** Recognise only Polaris's known radio stations; all other speech stays Hush music. */
object PolarisVoiceRouter {
    private val appPrefix = Regex(
        """^\s*(?:폴라리스(?:\s*언바운드)?|폴라이스(?:\s*언바운드)?|polaris(?:\s*unbound)?)(?:에서|으로|로)?\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val commandSuffix = Regex(
        """\s*(?:을|를)?\s*(?:틀어\s*줘|재생해\s*줘|들려\s*줘|플레이해\s*줘|틀어|재생)\s*[.!?。]*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Remove the app invocation, preserving the actual artist and song title. */
    fun musicQuery(raw: String): String {
        val original = raw.trim()
        if (original.isEmpty()) return original
        val cleaned = original.replace(appPrefix, "").replace(commandSuffix, "").trim()
        return cleaned.ifBlank { original }
    }

    fun radioIdForQuery(raw: String): String? {
        val compact = musicQuery(raw)
            .lowercase(Locale.ROOT)
            .replace("에프엠", "fm")
            .replace("에이엠", "am")
            .replace("점", ".")
            .replace(Regex("""[\s_\-]+"""), "")
        if (compact.isBlank()) return null
        fun has(vararg names: String): Boolean = names.any(compact::contains)

        // Frequencies overlap across cities: named overseas stations win first.
        if (has("kiis", "키스fm", "키이스", "키스라디오")) return "kiis"
        if (has("gallery41", "갤러리41", "갤러리포티원", "갤러리")) return "gallery"
        if (has("z100", "지백", "지100")) return "z100"
        if (has("power105.1", "power1051", "파워105.1", "파워1051")) return "power1051"
        if (has("wild94.9", "wild949", "와일드94.9")) return "wild949"
        if (has("alt98.7", "alt987", "얼터너티브98.7")) return "alt987"
        if (has("myfm104.3", "myfm1043", "마이fm104.3")) return "myfm1043"

        if (has("89.1", "891", "kbs", "케이비에스", "coolfm", "쿨fm")) return "kr1"
        if (has("91.9", "919", "fm4u", "fm포유", "mbcfm")) return "kr2"
        if (has("93.9", "939", "cbs", "씨비에스", "musicfm", "뮤직fm", "음악fm")) return "kr3"
        if (has("95.9", "959", "표준fm", "standardfm")) return "kr4"
        if (has("102.7", "1027", "afn", "에이에프엔", "eaglefm", "이글fm")) return "kr5"
        if (has("107.7", "1077", "sbs", "에스비에스", "powerfm", "파워fm")) return "kr6"
        if (has("mbc", "엠비씨") &&
            (has("라디오", "방송") || compact == "mbc" || compact == "엠비씨")
        ) return "kr2"
        return null
    }
}
