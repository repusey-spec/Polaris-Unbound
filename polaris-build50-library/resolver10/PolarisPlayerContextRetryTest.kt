package app.hush.music.polaris

import app.hush.music.innertube.*
import app.hush.music.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PolarisPlayerContextRetryTest {
    private val auth = PlaybackAuthState(cookie="SAPISID=test-only-cookie",dataSyncId="test-only-account",visitorData="test-only-visitor")
    private suspend fun request(status: HttpStatusCode, reason: String, setLogin: Boolean = true,
                                state: PlaybackAuthState = auth): List<HttpRequestData> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { data ->
            seen.add(data)
            if (seen.size == 1) respond(reason, status, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { encodeDefaults=true; ignoreUnknownKeys=true }) }
        }
        val tube = InnerTube()
        val field = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        (field.get(tube) as HttpClient).close()
        field.set(tube, client)
        try {
            runCatching { tube.player(YouTubeClient.ANDROID_MUSIC,"VVUvWmHAWDQ",null,null,setLogin=setLogin,authState=state) }
        } finally { client.close() }
        return seen
    }
    @Test fun invalidContextRetryKeepsCookieAndAuthorization() = runBlocking {
        val seen = request(HttpStatusCode.BadRequest, "{\"error\":\"INVALID_ARGUMENT\"}")
        assertEquals(2, seen.size)
        for (wire in seen) {
            assertTrue(wire.headers[HttpHeaders.Cookie].orEmpty().contains("test-only-cookie"))
            assertTrue(wire.headers[HttpHeaders.Authorization].orEmpty().startsWith("SAPISIDHASH "))
            assertEquals("test-only-visitor",wire.headers["X-Goog-Visitor-Id"])
        }
        val first=(seen[0].body as TextContent).text
        val second=(seen[1].body as TextContent).text
        assertTrue(first.contains("test-only-account"))
        assertFalse(second.contains("test-only-account"))
        assertTrue(second.contains("VVUvWmHAWDQ"))
    }
    @Test fun originMismatchIsNotRetriedWithoutContext() = runBlocking {
        assertEquals(1, request(HttpStatusCode.BadRequest,"Origin doesn't match Host INVALID_ARGUMENT").size)
    }
    @Test fun forbiddenIsNotRetriedAsAnonymous() = runBlocking {
        assertEquals(1, request(HttpStatusCode.Forbidden,"INVALID_ARGUMENT").size)
    }
    @Test fun anonymousRequestNeverGainsCookieOrAccountContext() = runBlocking {
        val seen=request(HttpStatusCode.OK,"{}",setLogin=false)
        assertEquals(1,seen.size)
        assertNull(seen.single().headers[HttpHeaders.Cookie])
        assertNull(seen.single().headers[HttpHeaders.Authorization])
        assertFalse((seen.single().body as TextContent).text.contains("test-only-account"))
    }
    @Test fun missingAccountContextCannotTriggerContextRetry() = runBlocking {
        assertEquals(1, request(HttpStatusCode.BadRequest,"INVALID_ARGUMENT",state=auth.copy(dataSyncId=null)).size)
    }
}
