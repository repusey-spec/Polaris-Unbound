package app.hush.music.polaris

import org.junit.Assert.*
import org.junit.Test

class PolarisResolverTest {
    @Test fun audioWinsOverEarlierStreamNamedLogo() {
        val body = """{"image":"https://example.org/stream-logo.png","audio":"https://cdn.example.org/live.m3u8?auth=abc"}"""
        assertEquals("https://cdn.example.org/live.m3u8?auth=abc", PolarisRadioResolver.extractStreamUrl(body))
    }
    @Test fun jsonpExtensionlessAudioIsRetained() {
        val body = """callback({"AACLiveUrl":"https://cdn.example.org/live?auth=abc","homepage":"https://example.org/"});"""
        assertEquals("https://cdn.example.org/live?auth=abc", PolarisRadioResolver.extractStreamUrl(body))
    }
    @Test fun htmlAndImageOnlyResponsesAreRejected() {
        assertNull(PolarisRadioResolver.extractStreamUrl("""{"image":"https://example.org/logo.png","url":"https://example.org/"}"""))
        assertNull(PolarisRadioResolver.extractStreamUrl("<html>https://example.org/error.html</html>"))
    }
    @Test fun escapedJsonUrlKeepsAuthenticationQuery() {
        val body = """{"audio":"https:\/\/cdn.example.org\/live.m3u8?a=1\u0026b=2"}"""
        assertEquals("https://cdn.example.org/live.m3u8?a=1&b=2", PolarisRadioResolver.extractStreamUrl(body))
    }
}
