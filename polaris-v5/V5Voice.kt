package app.hush.music.polarisv5

import java.util.Locale

/**
 * Voice command grammar ported from the frozen Polaris v0.58
 * PolarisMediaService.normalizeVoiceQuery() / radioIdFromVoice().
 * The Google Assistant speech-to-text engine is provided by Android Auto;
 * this adapter recognizes the *resulting text* and selects the radio item.
 *
 * Non-radio queries are NEVER claimed: Hush v13.14.8 continues its original
 * streaming song/artist/playlist voice search unchanged.
 */
object V5Voice {
    fun normalize(raw: String?): String {
        var q = raw.orEmpty().lowercase(Locale.KOREA).trim()
            .replace("엠피쓰리", "mp3")
            .replace("엠피3", "mp3")
            .replace("에프엠", "fm")
            .replace("에이엠", "am")
            .replace("점", ".")
            .replace(Regex("""\s+"""), " ")
        // Assistant phrases addressed to Polaris remain a media search,
        // not something our adapter should treat as a song title.
        q = q.replace(Regex("""(?i)^(폴라리스\s*(언바운드|unbound|v5)?\s*(에서|로)?\s*)"""), "")
        q = q.replace(Regex("""(?i)\b(틀어줘|재생해줘|재생|플레이|play|please)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        return q
    }

    fun radioIdForQuery(raw: String?): String? {
        val tight = normalize(raw).replace(" ", "")
        if (tight.isBlank()) return null
        // v0.58 matching precedence: KIIS wins over the shared AFN frequency.
        if (any(tight,"kiis","키스fm","키스에프엠","키이스","키스라디오"))
            return "kiis"
        if (any(tight,"gallery41","갤러리41","갤러리포티원","gallery","갤러리"))
            return "gallery"
        if (any(tight,"89.1","891","kbs","케이비에스","coolfm","쿨fm","쿨에프엠"))
            return "kr1"
        if (any(tight,"91.9","919","fm4u","fm포유","에프엠포유"))
            return "kr2"
        if (any(tight,"93.9","939","cbs","씨비에스","musicfm","뮤직fm","음악fm"))
            return "kr3"
        if (any(tight,"95.9","959","표준fm","표준에프엠","standardfm"))
            return "kr4"
        if (any(tight,"102.7","1027","afn","에이에프엔","eaglefm","이글fm"))
            return "kr5"
        if (any(tight,"107.7","1077","sbs","에스비에스","powerfm","파워fm","파워에프엠"))
            return "kr6"
        // Bare MBC prefers FM4U exactly as v0.58 did.
        if (any(tight,"mbc","엠비씨")) return "kr2"
        return null
    }

    private fun any(text: String, vararg tokens: String): Boolean =
        tokens.any { text.contains(it) }
}
