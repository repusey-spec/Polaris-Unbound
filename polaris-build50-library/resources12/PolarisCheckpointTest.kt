package app.hush.music.polaris

import app.hush.music.models.PersistPlayerState
import java.io.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class PolarisCheckpointTest {
    private val queue = Any()
    private fun key(generation: Long = 0, revision: Long = 0, index: Int = 0, id: String = "a", title: String = "liked", source: Any = queue) =
        PolarisQueueCheckpointKey(generation, revision, index, id, title, source)
    @Test fun threeHoursOfUnchangedTicksNeedOnlyOneFullQueueSnapshot() {
        var saved: PolarisQueueCheckpointKey? = null; var fullWrites = 0
        repeat(1080) { val next = key(); if (!next.matches(saved)) { fullWrites++; saved = next } }
        assertEquals(1, fullWrites)
    }
    @Test fun trackTimelineTitleAndSourceChangesRequireFullCheckpoint() {
        val saved = key()
        assertFalse(key(index = 1).matches(saved)); assertFalse(key(id = "b").matches(saved))
        assertFalse(key(revision = 1).matches(saved)); assertFalse(key(title = "other").matches(saved))
        assertFalse(key(source = Any()).matches(saved)); assertFalse(key(generation = 1).matches(saved))
    }
    @Test fun conflatedRequestsKeepOnlyLatestGenerationDuringBurst() {
        val requests = Channel<Long>(Channel.CONFLATED)
        repeat(100_000) { assertTrue(requests.trySend(it.toLong()).isSuccess) }
        assertEquals(99_999L, requests.tryReceive().getOrThrow()); assertTrue(requests.tryReceive().isFailure)
        requests.close()
    }
    @Test fun stateRoundTripKeepsTrackIdentityAndPositionWithExistingSerialUid() {
        val state = PersistPlayerState(false, 0, false, 1f, 12345L, 12, 3, currentMediaId = "track12")
        val bytes = ByteArrayOutputStream().apply { ObjectOutputStream(this).use { it.writeObject(state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as PersistPlayerState }
        assertEquals(state, restored)
        assertEquals(1L, ObjectStreamClass.lookup(PersistPlayerState::class.java).serialVersionUID)
        assertNull(PersistPlayerState(false, 0, false, 1f, 0, 0, 1).currentMediaId)
    }
}
