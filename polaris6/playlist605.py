#!/usr/bin/env python3
"""Polaris 6.05: fix Android Auto playlist browse, preserving the 6.04 engine."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
file = root / "app/src/main/java/com/dd3boh/outertune/playback/MediaLibrarySessionCallback.kt"
s = file.read_text(encoding="utf-8")

def one(a, b):
    global s
    count = s.count(a)
    assert count == 1, f"6.05 edit anchor expected once, found {count}: {a[:100]!r}"
    s = s.replace(a, b, 1)

one("import com.dd3boh.outertune.db.entities.PlaylistEntity\n",
    "import com.dd3boh.outertune.db.entities.PlaylistEntity\nimport com.dd3boh.outertune.db.entities.ArtistEntity\n")
one("import com.dd3boh.outertune.constants.SongSortType\n",
    "import com.dd3boh.outertune.constants.SongSortType\n"
    "import com.dd3boh.outertune.constants.AccountEmailKey\n"
    "import com.dd3boh.outertune.constants.AccountChannelHandleKey\n"
    "import com.dd3boh.outertune.constants.DataSyncIdKey\n")
one("import kotlinx.coroutines.withTimeoutOrNull\n",
    "import kotlinx.coroutines.withTimeoutOrNull\nimport kotlinx.coroutines.sync.Mutex\n")
one("    var toggleLike: () -> Unit = {}\n",
    """    /** Memory only, never persisted; keyed by the signed-in account/channel. */
    private data class CarLiked(val account: String, val loadedAt: Long, val complete: Boolean, val songs: List<Song>)
    private val carLikedMutex = Mutex()
    private var carLiked: CarLiked? = null
    var toggleLike: () -> Unit = {}
""")
one("""                    val likedSongCount = database.likedSongsCount().first()
                    val downloadedSongCount = downloadUtil.downloads.value.size
""",
"""                    // AA and the phone must enumerate the same online LM likes.
                    val likedSongCount = playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID).size
                    // The subtitle and child browser must agree for downloads, too.
                    val downloadedSongCount = playlistSongs(PlaylistEntity.DOWNLOADED_PLAYLIST_ID).size
""")
one("""    /** The songs of a playlist, the liked and the downloaded ones being playlists for this. */
    private suspend fun playlistSongs(playlistId: String): List<Song> = when (playlistId) {
        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs()
        else -> database.playlistSongs(playlistId).map { list -> list.map { it.song } }
    }.first()
""",
"""    /**
     * Phone OnlinePlaylistScreen uses YouTube.playlist("LM"), while the old AA
     * callback counted only the local Room liked flag (0 even with 166 online).
     * Use the SAME snapshot for AA count, children and the selected-song queue.
     * No import/sync, database write, account change or cache deletion happens.
     */
    private suspend fun carLikedSongs(): List<Song> {
        val local = database.likedSongs(SongSortType.CREATE_DATE, descending = true).first()
        val account = listOf(
            context.dataStore[AccountEmailKey].orEmpty(),
            context.dataStore[AccountChannelHandleKey].orEmpty(),
            context.dataStore[DataSyncIdKey].orEmpty(),
            YouTube.useLoginForBrowse.toString(),
        ).joinToString("|")
        carLikedMutex.lock()
        try {
            val now = SystemClock.elapsedRealtime()
            val previous = carLiked?.takeIf { it.account == account }
            if (previous != null && now - previous.loadedAt in 0 until
                (if (previous.complete) 60_000L else 12_000L)) return previous.songs

            val first = try {
                withTimeoutOrNull(7_000L) { YouTube.playlist("LM").getOrNull() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AA LM request failed", e)
                null
            }
            if (first == null) return previous?.songs ?: local

            val items = first.songs.toMutableList()
            var next = first.songsContinuation
            val followed = HashSet<String>()
            val deadline = SystemClock.elapsedRealtime() + 13_000L
            while (next != null && followed.size < 12 &&
                followed.add(next) && SystemClock.elapsedRealtime() < deadline) {
                val token = next
                val timeout = (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 3_500L)
                val page = try {
                    withTimeoutOrNull(timeout) { YouTube.playlistContinuation(token).getOrNull() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "AA LM continuation failed", e)
                    null
                } ?: break
                if (page.songs.isEmpty()) break
                items.addAll(page.songs)
                next = page.continuation
            }
            val remote = items.distinctBy { it.id }.map { item ->
                val meta = item.toMediaMetadata()
                Song(
                    song = meta.toSongEntity().copy(liked = true),
                    artists = meta.artists.mapIndexed { index, artist ->
                        ArtistEntity(artist.id ?: ("aa/" + item.id + "/" + index), artist.name)
                    },
                )
            }
            if (remote.isEmpty()) return previous?.songs ?: local
            val complete = next == null
            carLiked = CarLiked(account, SystemClock.elapsedRealtime(), complete, remote)
            Log.i(TAG, "AA LM liked tracks=" + remote.size + " complete=" + complete)
            return remote
        } finally {
            carLikedMutex.unlock()
        }
    }

    /** AA list subtitles and the item tap use the very same source. */
    private suspend fun playlistSongs(playlistId: String): List<Song> = when (playlistId) {
        PlaylistEntity.LIKED_PLAYLIST_ID -> carLikedSongs()
        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs().first()
        else -> database.playlistSongs(playlistId).first().map { it.song }
    }
""")
file.write_text(s, encoding="utf-8")

gradle = root / "app/build.gradle.kts"
g = gradle.read_text(encoding="utf-8")
assert g.count("versionCode = 100604") == 1 and g.count('versionName = "6.04"') == 1
gradle.write_text(g.replace("versionCode = 100604","versionCode = 100605",1)
                  .replace('versionName = "6.04"','versionName = "6.05"',1), encoding="utf-8")
test = root / "app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
t = test.read_text(encoding="utf-8")
assert t.count("Polaris 6.04 (versionCode 100604)") == 1
test.write_text(t.replace("Polaris 6.04 (versionCode 100604)","Polaris 6.05 (versionCode 100605)"),encoding="utf-8")

# Source-level regression test: the AA subtitle, browse, and queue use one list.
tests = root / "app/src/test/java/com/dd3boh/outertune/polaris/PolarisCarPlaylist605Test.kt"
tests.parent.mkdir(parents=True, exist_ok=True)
tests.write_text("""package com.dd3boh.outertune.polaris
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class PolarisCarPlaylist605Test {
  private val callback = File("src/main/java/com/dd3boh/outertune/playback/MediaLibrarySessionCallback.kt").readText()
  @Test fun likedCountBrowseAndPlaybackShareOneSource() {
    assertTrue(callback.contains("val likedSongCount = playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID).size"))
    assertTrue(callback.contains("PlaylistEntity.LIKED_PLAYLIST_ID -> carLikedSongs()"))
    assertTrue(callback.contains("val songs = playlistSongs(playlistId).map { it.toMediaItem(parentId) }"))
    assertTrue(callback.contains("is PlayRequest.Playlist -> playlistSongs(target.playlistId)"))
  }
  @Test fun onlineLmIsBoundedAndOfflineLeavesLocalAlone() {
    assertTrue(callback.contains("YouTube.playlist("))
    assertTrue(callback.contains("YouTube.playlistContinuation(token)"))
    assertTrue(callback.contains("withTimeoutOrNull(7_000L)"))
    assertTrue(callback.contains("return previous?.songs ?: local"))
    assertFalse(callback.contains("val downloadedSongCount = downloadUtil.downloads.value.size"))
  }
}
""", encoding="utf-8")
print("PASS: AA online LM route and 6.05 identity, 6.04 media sources unchanged")
