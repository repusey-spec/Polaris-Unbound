package app.hush.music.polaris

import android.app.Application
import app.hush.music.innertube.*
import app.hush.music.constants.*
import app.hush.music.utils.*
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PolarisAccountRecovery15Test {
    @Test fun failedClientCannotDeleteStoredOrActiveAccount() = runBlocking {
        val context = RuntimeEnvironment.getApplication<Application>()
        context.dataStore.edit { it[InnerTubeCookieKey] = "SAPISID=test-cookie"; it[DataSyncIdKey] = "selected-channel" }
        val initial = PlaybackAuthState(cookie = "SAPISID=test-cookie", dataSyncId = "selected-channel", visitorData = "visitor")
        YouTube.authState = initial
        var calls = 0
        val failure = YTPlayerUtils.InvalidPlaybackLoginContextException("song1234567", "https://music.youtube.com/", IllegalArgumentException("client parameter"))
        val result = context.retryWithoutPlaybackLoginContext<Int> { calls++; Result.failure(failure) }
        assertSame(failure, result.exceptionOrNull()); assertEquals(1, calls)
        assertEquals(initial, YouTube.authState)
        assertEquals("selected-channel", context.dataStore.data.first()[DataSyncIdKey])
    }
    @Test fun successfulPlaybackDoesNotOverwriteSelectedChannel() = runBlocking {
        val context = RuntimeEnvironment.getApplication<Application>()
        val initial = PlaybackAuthState(cookie = "SAPISID=existing", dataSyncId = "brand-channel")
        YouTube.authState = initial
        assertEquals(7, context.retryWithoutPlaybackLoginContext { Result.success(7) }.getOrThrow())
        assertEquals(initial, YouTube.authState)
    }
    @Test fun cancellationStillPropagatesToCaller() = runBlocking {
        val context = RuntimeEnvironment.getApplication<Application>()
        val cancel = CancellationException("superseded")
        try { context.retryWithoutPlaybackLoginContext<Int> { throw cancel }; fail("cancel swallowed") }
        catch (e: CancellationException) { assertSame(cancel, e) }
    }
    @Test fun nestedResolutionUsesTheSamePhysicalRequestBudget() = runBlocking {
        PlaybackTrace.resolution("song1234567", "test") {
            val outer = currentCoroutineContext()[StreamRequestBudget]!!
            outer.acquire()
            PlaybackTrace.resolution("song1234567", "retry") {
                val inner = currentCoroutineContext()[StreamRequestBudget]!!
                assertSame(outer, inner); inner.acquire()
            }
            assertEquals(2, outer.used())
        }
    }
    @Test fun nextResolutionStartsWithANewBudget() = runBlocking {
        var first: StreamRequestBudget? = null
        PlaybackTrace.resolution("song1234567", "test") { first = currentCoroutineContext()[StreamRequestBudget]!!; first!!.acquire() }
        PlaybackTrace.resolution("song1234567", "test") { val next = currentCoroutineContext()[StreamRequestBudget]!!; assertNotSame(first, next); assertEquals(0, next.used()) }
    }
    @Test fun allWireAttemptsIncludingRetriesCountTowardsLimit() {
        val b = StreamRequestBudget()
        repeat(18) { assertEquals(it + 1, b.acquire()) }
        try { b.acquire(); fail("19th request admitted") } catch (_: StreamRequestBudgetExceeded) {}
        assertEquals(18, b.used())
    }
    @Test fun concurrentAttemptsCannotExceedBudget() = runBlocking {
        val b = StreamRequestBudget(8)
        val admitted = (1..40).map { async(Dispatchers.Default) { try { b.acquire(); true } catch (_: StreamRequestBudgetExceeded) { false } } }.awaitAll()
        assertEquals(8, admitted.count { it })
    }
    @Test fun streamHeaderFlagsSurviveCredentialRedaction() {
        val line = "loginCookie=true cookieHeader=true authorizationHeader=true dataSync=true playbackContext=true poToken=false authEpoch=3 authMatchesGlobal=true"
        assertEquals(line, PolarisSourcePolicy.redact(line))
    }
    @Test fun actualCookieAndAuthorizationRemainRedacted() {
        val text = "Cookie: SAPISID=secret-value\nAuthorization: SAPISIDHASH secret-hash\nloginCookie=true dataSync=false"
        val clean = PolarisSourcePolicy.redact(text)
        assertFalse(clean.contains("secret-value")); assertFalse(clean.contains("secret-hash"))
        assertTrue(clean.contains("loginCookie=true dataSync=false"))
    }
    @Test fun actualTokensAndSignedUrlRemainRedacted() {
        val text = "refresh_token=secret-token\nhttps://example.test/audio?signature=secret-signature\npoToken=true"
        val clean = PolarisSourcePolicy.redact(text)
        assertFalse(clean.contains("secret-token")); assertFalse(clean.contains("secret-signature")); assertTrue(clean.contains("poToken=true"))
    }
    @Test fun truncatedOrWrongChannelRemoteListCannotRemoveExistingLikes() {
        val existing = (1..166).associate { "song$it" to true }
        val partialRemote = (1..13).map { "song$it" }.toSet()
        val after = existing.mapValues { (id, liked) -> PolarisLikedSnapshotPolicy.likedAfterSnapshot(liked, id in partialRemote, null) }
        assertEquals(166, after.values.count { it })
    }
    @Test fun explicitUnlikeWinsOverStaleRemoteTrue() {
        assertFalse(PolarisLikedSnapshotPolicy.likedAfterSnapshot(true, true, false))
    }
    @Test fun explicitRelikeWinsOverMissingRemoteEntry() {
        assertTrue(PolarisLikedSnapshotPolicy.likedAfterSnapshot(false, false, true))
    }
    @Test fun remoteLikeStillRestoresLocallyMissingLike() {
        assertTrue(PolarisLikedSnapshotPolicy.likedAfterSnapshot(false, true, null))
    }
    @Test fun repeatedSyncPreservesExistingLikedDate() {
        assertEquals(1000L, PolarisLikedSnapshotPolicy.likedDate(1000L, 9000L))
        assertEquals(9000L, PolarisLikedSnapshotPolicy.likedDate<Long>(null, 9000L))
    }
}
