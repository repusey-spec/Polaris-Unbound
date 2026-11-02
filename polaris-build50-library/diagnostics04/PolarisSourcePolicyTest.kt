package app.hush.music.polaris

import org.junit.Assert.*
import org.junit.Test

class PolarisSourcePolicyTest {
    @Test fun routeBelongsToItemNotPreviousMode() {
        assertTrue(PolarisSourcePolicy.isRadio("polaris:station:kr1"))
        assertFalse(PolarisSourcePolicy.isRadio("abcdefghijk"))
        assertFalse(PolarisSourcePolicy.isRadio("playlist/polaris:station:kr1"))
        assertTrue(PolarisSourcePolicy.isRadio("polaris:station:kiis"))
    }
    @Test fun rejectStreamNamedImagesAndWebPages() {
        assertEquals(-1, PolarisSourcePolicy.audioUrlScore("https://example.org/stream-logo.png", "audio"))
        assertEquals(-1, PolarisSourcePolicy.audioUrlScore("https://example.org/live", "homepage"))
        assertEquals(-1, PolarisSourcePolicy.audioUrlScore("https://example.org/page"))
        assertTrue(PolarisSourcePolicy.audioUrlScore("https://example.org/live.m3u8?token=secret") > 0)
        assertTrue(PolarisSourcePolicy.audioUrlScore("https://example.org/live", "AACLiveUrl") > 0)
        assertEquals(-1, PolarisSourcePolicy.audioUrlScore("file:///tmp/audio.mp3", "audio"))
    }
    @Test fun reportRemovesCredentialsAndUrlQuery() {
        val safe = PolarisSourcePolicy.redact("HTTP 403 https://user:pass@example.org/audio?token=secret Cookie: SECRET Authorization=BEARER access_token=MYTOKEN")
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("MYTOKEN"))
        assertFalse(safe.contains("user:pass"))
        assertTrue(safe.contains("HTTP 403"))
        assertTrue(safe.contains("example.org"))
    }
}
