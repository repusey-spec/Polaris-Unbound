package app.hush.music.polaris

import app.hush.music.innertube.PlaybackAuthState
import app.hush.music.innertube.PlaybackTrace
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections

class PolarisStreamDiagnosticsTest {
    private val events = Collections.synchronizedList(mutableListOf<String>())
    @Before fun setup() { PlaybackTrace.sink = { events.add(it) } }
    @After fun cleanup() { PlaybackTrace.sink = null }
    @Test fun missingPlaybackContextVisibleWithoutCookieValue() = runBlocking {
        val auth = PlaybackAuthState(cookie="SAPISID=SECRET_COOKIE")
        PlaybackTrace.player("VVUvWmHAWDQ", "ANDROID_MUSIC", false, true, auth, auth, false) { }
        val log=events.joinToString("\n")
        assertTrue(log.contains("cookiePresent=true")); assertTrue(log.contains("playbackContext=false"))
        assertFalse(log.contains("SECRET_COOKIE")); assertFalse(log.contains("SAPISID="))
    }
    @Test fun completeContextAndMismatchedSnapshotVisible() = runBlocking {
        val auth=PlaybackAuthState(cookie="SAPISID=secret",dataSyncId="secret-account",visitorData="secret-visitor")
        PlaybackTrace.player("VVUvWmHAWDQ", "WEB_REMIX",true,true,auth,PlaybackAuthState.EMPTY,true) { }
        val log=events.joinToString("\n")
        assertTrue(log.contains("playbackContext=true"));assertTrue(log.contains("authMatchesGlobal=false"))
        assertFalse(log.contains("secret"))
    }
    @Test fun serverReasonCategorizedNeverCopied() {
        PlaybackTrace.response("req", "VVUvWmHAWDQ",null,"LOGIN_REQUIRED","Sign in cookie=SECRET https://signed.invalid/?token=SECRET person@example.com",0,0,0)
        val log=events.single();assertTrue(log.contains("reason=login-required")); assertFalse(log.contains("SECRET"));assertFalse(log.contains("@"));assertFalse(log.contains("https:"))
    }
    @Test fun responseIdSubstitutionIsVisible() {
        PlaybackTrace.response("req","VVUvWmHAWDQ","YfnfWqMFozU","OK",null,2,1,1)
        assertTrue(events.single().contains("idMatch=false"));assertTrue(events.single().contains("direct=1 cipher=1"))
    }
    @Test fun absentResponseIdIsUnknown() {
        PlaybackTrace.response("req","VVUvWmHAWDQ",null,"LOGIN_REQUIRED",null,0,0,0)
        assertTrue(events.single().contains("idMatch=unknown"))
    }
    @Test fun idAndClientRejectUntrustedStrings() {
        assertEquals("unknown", PlaybackTrace.id("cookie=secret"));assertEquals("other",PlaybackTrace.label("https://secret.invalid"))
        assertEquals("VVUvWmHAWDQ",PlaybackTrace.id("VVUvWmHAWDQ"))
    }
    @Test fun sinkFailureDoesNotChangePlaybackResult() = runBlocking {
        PlaybackTrace.sink={throw IllegalStateException("storage failure")}
        val result=PlaybackTrace.resolution("VVUvWmHAWDQ","download") { 7 }
        assertEquals(7,result)
    }
    @Test fun cancellationPreservedAndReported() = runBlocking {
        try { PlaybackTrace.resolution("VVUvWmHAWDQ","download") { throw CancellationException("secret") };fail() }
        catch (_: CancellationException) { }
        assertTrue(events.last().contains("outcome=cancelled-or-deadline"));assertFalse(events.joinToString().contains("secret"))
    }
    @Test fun concurrentRequestsHaveSeparateContexts() = runBlocking {
        coroutineScope {
            listOf("VVUvWmHAWDQ","YfnfWqMFozU").map { id -> async(Dispatchers.Default) {
                PlaybackTrace.resolution(id,"download") {
                    yield()
                    PlaybackTrace.player(id,"WEB_REMIX",true,true,PlaybackAuthState.EMPTY,PlaybackAuthState.EMPTY,false) { }
                }
            } }.awaitAll()
        }
        val starts=events.filter { it.startsWith("player-start") }
        assertEquals(2, starts.size)
        starts.forEach { line ->
            val original=Regex("original=([^ ]+)").find(line)!!.groupValues[1]
            assertTrue(line.contains("requested=$original"));assertFalse(line.contains("attempt=unscoped"))
        }
        assertEquals(2,starts.map { Regex("request=([^ ]+)").find(it)!!.groupValues[1] }.distinct().size)
    }
    @Test fun successfulAndFailedResponsesComparableBySameEpoch() = runBlocking {
        val auth=PlaybackAuthState.EMPTY
        for (id in listOf("VVUvWmHAWDQ","YfnfWqMFozU"))
            PlaybackTrace.player(id,"ANDROID_MUSIC",false,false,auth,auth,false) { request ->
                PlaybackTrace.response(request,id,id,if(id.startsWith("VV")) "LOGIN_REQUIRED" else "OK",null,0,0,0)
            }
        val epochs=events.filter { it.startsWith("player-start") }.map { Regex("authEpoch=([0-9]+)").find(it)!!.groupValues[1] }
        assertEquals(1,epochs.distinct().size);assertEquals(2,events.count {it.startsWith("player-response")})
    }
    @Test fun browseFlagsComparableAndNoAuthValues() {
        PlaybackTrace.browse("WEB_REMIX",PlaybackAuthState(cookie="SAPISID=secret",dataSyncId="account-secret"),true,true,true,true)
        assertTrue(events.single().contains("authorizationHeader=true"));assertFalse(events.single().contains("secret"))
    }
    @Test fun knownServerReasonsAreDistinct() {
        assertEquals("bot-confirmation",PlaybackTrace.reason("LOGIN_REQUIRED","Confirm you're not a bot"))
        assertEquals("age-confirmation",PlaybackTrace.reason("UNPLAYABLE","연령 확인"))
        assertEquals("region-restriction",PlaybackTrace.reason("UNPLAYABLE","not in your country"))
        assertEquals("other-server-reason",PlaybackTrace.reason("ERROR","arbitrary secret"))
    }
    @Test fun nestedDownloadResolutionKeepsProviderAttempt() = runBlocking {
        PlaybackTrace.resolution("VVUvWmHAWDQ","download-file") {
            PlaybackTrace.resolution("VVUvWmHAWDQ","download") { PlaybackTrace.stage("metadata-start","VVUvWmHAWDQ") }
        }
        assertEquals(1,events.count {it.startsWith("resolution-start")})
        assertEquals(1,events.count {it.startsWith("resolution-end")})
    }
    @Test fun resolutionFailurePreservesOriginalException() = runBlocking {
        val error=IllegalStateException("secret")
        try { PlaybackTrace.resolution("VVUvWmHAWDQ","download-file") { throw error };fail() }
        catch (caught: IllegalStateException) {
            // Coroutine stack-trace recovery can copy an exception and retain its original cause.
            assertEquals(error.javaClass, caught.javaClass)
            assertEquals(error.message, caught.message)
            assertTrue(generateSequence<Throwable>(caught) { it.cause }.take(12).any { it === error })
        }
        assertTrue(events.last().contains("outcome=failure"));assertFalse(events.joinToString().contains("secret"))
    }
}
