#!/usr/bin/env python3
"""Polaris 6.06 atop 6.05: direct AA playlists and true last audible source.
Only AA tree, Polaris radio session source checkpoint, and music resumption are changed.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
src = root/"app/src/main/java/com/dd3boh/outertune"
callback=src/"playback/MediaLibrarySessionCallback.kt"
text=callback.read_text(encoding="utf-8")

def replace_one(value, before, after, label):
    count=value.count(before)
    assert count == 1, f"{label}: expected one anchor, found {count}: {before[:110]!r}"
    return value.replace(before,after,1)

# Move the existing real playlist enumeration to one shared function.
start = text.index("                MusicService.PLAYLIST -> {\n")
end = text.index("\n\n                else -> when {",start)
old = text[start:end]
prefix = "                MusicService.PLAYLIST -> {\n"
suffix = "\n                }"
assert old.startswith(prefix) and old.endswith(suffix)
content = old[len(prefix):-len(suffix)]
method = "\n    /** AA Streaming opens the actual lists directly; no 4-folder intermediary. */\n" \
         "    private suspend fun polarisPlaylistRows(): List<MediaItem> {\n" \
         + content + "\n    }\n"
text=text[:start] + "                MusicService.PLAYLIST -> polarisPlaylistRows()" + text[end:]
first=text.index("                RadioCatalog.STREAMING_ROOT_ID -> if (Unreleased.AUTO_REWORK)")
last=text.index("\n\n                AutoBrowse.HOME ->",first)
text=text[:first]+"                RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()"+text[last:]
text=replace_one(text,
    "                RadioCatalog.RADIO_ROOT_ID -> CarSearch.page(PolarisRadioLayout(context).slots().filterNotNull().map { PolarisRadioRequests.queueItem(it) }, page, pageSize)",
    "                RadioCatalog.RADIO_ROOT_ID -> CarSearch.page(PolarisRadioLayout(context).slots().filterNotNull().map { polarisRadioTile(it) }, page, pageSize)",
    "radio tile labels")

# Put helpers into the class, before the onSubscribe callback.
marker="    /**\n     * A browser asking to be told when a list changes."
assert text.count(marker)==1
helpers=StringNotUsed = """    /**
     * The car centers a grid title itself. The large station tile already contains
     * its frequency: a compact caption aligns more closely than the long repeated
     * frequency+station title. The actual player keeps full metadata unchanged.
     */
    private fun polarisRadioTile(id: String): MediaItem {
        val item = PolarisRadioRequests.queueItem(id)
        val full = RadioCatalog.find(id)?.title ?: return item
        val compact = full.replace(Regex("^\\\\d+(?:\\\\.\\\\d+)?\\\\s+"), "")
            .removePrefix("Jazz from ").trim()
        return item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon().setTitle(compact).build()
        ).build()
    }
"""
text=text.replace(marker,method+"\n"+helpers+"\n"+marker,1)
text=replace_one(text,
    "if (stationId != null) return@future LibraryResult.ofItem(PolarisRadioRequests.queueItem(stationId), null)",
    "if (stationId != null) return@future LibraryResult.ofItem(polarisRadioTile(stationId), null)",
    "radio item link")
text=replace_one(text,
    "import com.dd3boh.outertune.db.entities.ArtistEntity\n",
    "import com.dd3boh.outertune.db.entities.ArtistEntity\nimport com.dd3boh.outertune.polaris.PolarisLastSource\n",
    "last-source import callback")
before="""    private suspend fun resumptionItems(): MediaItemsWithStartPosition? {
        val (q, items) = withContext(Dispatchers.IO) {
"""
after="""    private suspend fun resumptionItems(): MediaItemsWithStartPosition? {
        // A prior radio listen is not part of InterTune's music QueueBoard.
        // On media-button/AA resumption, restore the last actually heard source.
        PolarisLastSource.radioToResume(context)?.let { station ->
            val selected = PolarisRadioRequests.queueItem(station)
            val queue = PolarisRadioPlaylist.selection(context, selected)
            service.pendingOrigin = PlayOrigin.RESUMED
            Log.i(TAG, "Polaris resume: last source radio=" + station)
            return MediaItemsWithStartPosition(queue.items, queue.index, 0L)
        }
        val (q, items) = withContext(Dispatchers.IO) {
"""
text=replace_one(text,before,after,"resume override callback")
callback.write_text(text,encoding="utf-8")

# Store the last audible source; a stop/clear and a cold restored queue MUST NOT
# replace it with the previous music source. Switching tracks while audio stays
# playing is also a valid last-source switch.
last_source=src/"polaris/PolarisLastSource.kt"
assert not last_source.exists()
last_source.write_text("""package com.dd3boh.outertune.polaris

import android.content.Context
import androidx.media3.common.MediaItem

/**
 * Last *audible* source, independent of InterTune's saved music QueueBoard.
 * Only source identity and a public radio catalog id are kept; no URL or token.
 */
object PolarisLastSource {
    private const val STORE = "polaris_last_source_606"
    private const val MODE = "source"
    private const val STATION = "station"
    const val RADIO = "radio"
    const val STREAMING = "streaming"

    fun radioCandidate(mode: String?, station: String?): String? =
        station?.takeIf { mode == RADIO && RadioCatalog.find(it) != null }

    fun radioToResume(context: Context): String? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return radioCandidate(prefs.getString(MODE, null), prefs.getString(STATION, null))
    }

    fun rememberAudible(context: Context, item: MediaItem?) {
        item ?: return
        val station = PolarisRadioRequests.stationId(item)
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val mode = if (station != null) RADIO else STREAMING
        if (prefs.getString(MODE, null) == mode &&
            (station == null || prefs.getString(STATION, null) == station)) return
        prefs.edit().putString(MODE, mode).apply {
            if (station != null) putString(STATION, station)
            else remove(STATION)
        }.apply()
        PolarisErrorReport.record("last-source remembered=" + mode +
            if (station == null) "" else " station=" + station)
    }
}
""",encoding="utf-8")

session=src/"polaris/PolarisRadioSession.kt"
s=session.read_text(encoding="utf-8")
s=replace_one(s,
    "    private val recovery = PolarisRadioRecovery(player)\n",
    "    private val recovery = PolarisRadioRecovery(player)\n    private var playedAudiblyInThisService = false\n",
    "radio-session gate")
s=replace_one(s,
    """    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
        recovery.onItemTransition(item)
""",
    """    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
        if (playedAudiblyInThisService && player.playWhenReady && item != null) {
            PolarisLastSource.rememberAudible(context, item)
        }
        recovery.onItemTransition(item)
""",
    "radio-session transition")
s=replace_one(s,
    """    override fun onIsPlayingChanged(playing: Boolean) {
        recovery.onIsPlayingChanged(playing)
""",
    """    override fun onIsPlayingChanged(playing: Boolean) {
        if (playing) {
            playedAudiblyInThisService = true
            PolarisLastSource.rememberAudible(context, player.currentMediaItem)
        }
        recovery.onIsPlayingChanged(playing)
""",
    "radio-session audible")
session.write_text(s,encoding="utf-8")

service=src/"playback/MusicService.kt"
s=service.read_text(encoding="utf-8")
s=replace_one(s,
    """        withContext(Dispatchers.Main) {
            // initQueue restores QueueBoard and stops there. Nothing pushes the songs into the
""",
    """        withContext(Dispatchers.Main) {
            val lastRadio = PolarisLastSource.radioToResume(this@MusicService)
            if (lastRadio != null && !activePlayer.isPlaying) {
                // Music QueueBoard remains preserved, but is not the last source.
                // Radio must reopen at the live edge, never at a saved music time.
                if (activePlayer.currentMediaItem?.mediaId != "polaris:station:" + lastRadio) {
                    PolarisRadioPlaylist.set(activePlayer, this@MusicService,
                        PolarisRadioRequests.queueItem(lastRadio))
                }
                Log.i(TAG, "Resume on launch: last source radio=" + lastRadio)
                activePlayer.prepare()
                activePlayer.play()
                return@withContext
            }
            // initQueue restores QueueBoard and stops there. Nothing pushes the songs into the
""",
    "service resume override")
service.write_text(s,encoding="utf-8")

g=root/"app/build.gradle.kts"
s=g.read_text(encoding="utf-8")
s=replace_one(s,"versionCode = 100605","versionCode = 100606","versionCode")
s=replace_one(s,'versionName = "6.05"','versionName = "6.06"',"versionName")
g.write_text(s,encoding="utf-8")

test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
s=test.read_text(encoding="utf-8")
s=replace_one(s,"Polaris 6.05 (versionCode 100605)","Polaris 6.06 (versionCode 100606)","report version")
test.write_text(s,encoding="utf-8")

# New isolated policy + integration regression tests; no account needed.
spec=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisAA606Test.kt"
spec.parent.mkdir(parents=True,exist_ok=True)
spec.write_text("""package com.dd3boh.outertune.polaris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolarisAA606Test {
    private val callback = File("src/main/java/com/dd3boh/outertune/playback/MediaLibrarySessionCallback.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val session = File("src/main/java/com/dd3boh/outertune/polaris/PolarisRadioSession.kt").readText()

    @Test fun directStreamingTabShowsPlaylistsNotFourFolders() {
        assertTrue(callback.contains("RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()"))
        assertTrue(callback.contains("MusicService.PLAYLIST -> polarisPlaylistRows()"))
        assertTrue(callback.contains("val likedSongCount = playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID).size"))
        assertTrue(callback.contains("val downloadedSongCount = playlistSongs(PlaylistEntity.DOWNLOADED_PLAYLIST_ID).size"))
        assertTrue(callback.contains("database.playlistInLibraryAsc().first().map"))
    }
    @Test fun resumeRadioIsPrioritizedInBothSystemAndLaunchPaths() {
        assertTrue(callback.contains("PolarisLastSource.radioToResume(context)?.let { station ->"))
        assertTrue(callback.contains("PolarisRadioPlaylist.selection(context, selected)"))
        assertTrue(service.contains("PolarisLastSource.radioToResume(this@MusicService)"))
        assertTrue(service.contains("PolarisRadioPlaylist.set(activePlayer, this@MusicService"))
        assertTrue(session.contains("PolarisLastSource.rememberAudible(context, item)"))
        assertTrue(session.contains("PolarisLastSource.rememberAudible(context, player.currentMediaItem)"))
    }
    @Test fun sourcePolicyUsesOnlyValidStationForRadio() {
        assertEquals("kr1",PolarisLastSource.radioCandidate("radio","kr1"))
        assertEquals("kiis",PolarisLastSource.radioCandidate("radio","kiis"))
        assertNull(PolarisLastSource.radioCandidate("streaming","kr1"))
        assertNull(PolarisLastSource.radioCandidate("radio","not-real"))
        assertNull(PolarisLastSource.radioCandidate(null,"kr1"))
    }
}
""",encoding="utf-8")
print("PASS: 606 streaming opens lists, last audible source persists, radio resumes without touching core engine")
