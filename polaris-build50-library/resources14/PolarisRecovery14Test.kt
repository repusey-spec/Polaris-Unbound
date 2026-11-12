package app.hush.music.polaris

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PolarisRecovery14Test {
    @Test fun unlikeSurvivesStoreRecreationAndStaleRemoteLike() {
        val c = RuntimeEnvironment.getApplication<Application>()
        PolarisLikeIntents(c).record("account1", "song", false)
        assertEquals(false, PolarisLikeIntents(c).desired("account1", "song"))
    }
    @Test fun relikeReplacesUnlike() {
        val s = PolarisLikeIntents(RuntimeEnvironment.getApplication())
        s.record("a", "s", false); s.record("a", "s", true)
        assertEquals(true, s.desired("a", "s"))
    }
    @Test fun accountsAndSongsDoNotShareIntent() {
        val s = PolarisLikeIntents(RuntimeEnvironment.getApplication())
        s.record("one", "x", false)
        assertNull(s.desired("two", "x")); assertNull(s.desired("one", "y"))
    }
    @Test fun onlineRetryKeepsRangeButCannotReadTheOldCacheKey() {
        val old = androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.parse("song"))
            .setKey("song").setPosition(1024L).setLength(4096L).build()
        val next = PolarisOnlineRecovery.remoteSpec(old, "song", android.net.Uri.parse("https://example.test/audio"))
        assertEquals(1024L, next.position); assertEquals(4096L, next.length)
        assertNotEquals(old.key, next.key); assertEquals("song", old.key)
    }
    @Test(expected = IllegalArgumentException::class) fun onlineRetryRejectsAnotherLocalFile() {
        PolarisOnlineRecovery.remoteSpec(androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.parse("song")).build(),
            "song", android.net.Uri.parse("file:///broken.flac"))
    }
    @Test fun prefetchingNeighbourCannotEraseCurrentLocalEvidence() {
        val routes = PolarisLocalRoutes()
        routes.openedFile("playing"); routes.openedFile("prefetched")
        assertTrue(routes.wasFile("playing")); assertTrue(routes.wasFile("prefetched"))
        routes.openedRemote("playing")
        assertFalse(routes.wasFile("playing")); assertTrue(routes.wasFile("prefetched"))
    }
    @Test fun localEvidenceIsBounded() {
        val routes = PolarisLocalRoutes()
        repeat(1000) { routes.openedFile("track$it") }
        assertFalse(routes.wasFile("track0")); assertTrue(routes.wasFile("track999"))
        routes.openedFile("active")
        repeat(100) { routes.openedFile("active"); routes.openedFile("new$it") }
        assertTrue(routes.wasFile("active"))
    }
    @Test fun acknowledgedLikeEventuallyAllowsLaterRemoteChanges() {
        var now = 1000L
        val store = PolarisLikeIntents(RuntimeEnvironment.getApplication()) { now }
        store.record("expiry", "song", false); store.acknowledge("expiry", "song", false)
        assertEquals(false, store.desired("expiry", "song"))
        now += 31_000L
        assertNull(store.desired("expiry", "song"))
        assertEquals(false, store.desired("expiry", "song", 500L))
    }
    @Test fun outboxKeepsOfflineUnlikeAndDropsAcknowledgedEntry() {
        val store = PolarisLikeIntents(RuntimeEnvironment.getApplication())
        store.record("outbox", "song", false)
        assertTrue(store.pending("outbox").contains("song" to false))
        store.acknowledge("outbox", "song", false)
        assertFalse(store.pending("outbox").contains("song" to false))
    }
}
