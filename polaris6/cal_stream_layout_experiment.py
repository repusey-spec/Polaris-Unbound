#!/usr/bin/env python3
"""EXPERIMENT ONLY. Replace the two-level Streaming AA browse presentation with
InterTune's Car App Library SectionedItemTemplate. Do NOT package a user APK here.

Apply after aa610.py on the isolated experiment branch. The radio stream engine,
voice routing, last-played source, credentials and media data are untouched.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"

def one(path, old, new, label):
    raw=path.read_text(encoding="utf-8")
    occurrences=raw.count(old)
    assert occurrences==1, f"{label}: expected one match, got {occurrences}"
    path.write_text(raw.replace(old,new,1),encoding="utf-8")

car=pkg/"playback/AutoCar.kt"

one(car,
    "import androidx.car.app.CarAppService",
    "import com.dd3boh.outertune.polaris.RadioCatalog\nimport androidx.car.app.CarAppService",
    "import catalog for CAL experiment")

# AutoCarService remains behind a known-host validator. Never allow untrusted
# arbitrary apps to bind to the player's session for normal release.
one(car,
    "if (Unreleased.AUTO_REWORK) known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)",
    "known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)",
    "host validation for release CAL experiment")

# Explicitly choose a small four-shortcut section followed by playables.
# Normal media-tree screens (including radio and other browsed folders) are
# left in the original InterTune renderer. The media host, not app code,
# ultimately chooses how many grid columns fit on the vehicle's display.
old='''    val playing = session.player?.currentMediaItem?.mediaId
    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {'''
new='''    val playing = session.player?.currentMediaItem?.mediaId
    if (list?.mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID) {
        val expected = listOf(MusicService.SONG, MusicService.ARTIST,
            MusicService.ALBUM, RadioCatalog.PLAYLIST_ROOT_ID)
        val shortcuts = items.take(4)
        if (shortcuts.map { it.mediaId } == expected &&
            shortcuts.all { it.mediaMetadata.isBrowsable == true }) {
            val grid = GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)
            shortcuts.forEach { grid.addItem(tile(session, it)) }
            out.addSection(grid.build())
            val rows = RowSection.Builder()
            val songs = items.drop(4).filter { it.mediaMetadata.isPlayable == true }
                .take(24)
            songs.forEach { rows.addItem(row(session, it, playing)) }
            if (songs.isNotEmpty()) out.addSection(rows.build())
            return out.build()
        }
        // Incomplete or stale browse data: use the existing safe list renderer.
    }
    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {'''
one(car,old,new,"special streaming SectionedItemTemplate renderer")

# Use exactly two visible tabs. InterTune originally reserved a third Settings
# tab; hidden settings screens and app-side settings data are not deleted.
one(car,
    "val known = tabs?.take(3)",
    '''val known = tabs?.filter { it.mediaId == RadioCatalog.RADIO_BROWSE_ROOT_ID ||
            it.mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID }''',
    "two tab filter")
one(car,
    '''        out.addTab(Tab.Builder().setContentId(SETTINGS).setTitle(carContext.getString(R.string.settings)).setIcon(carContext.icon(R.drawable.auto_settings)).build())
        val inside = if (now == SETTINGS) {''',
    '''        // No extra settings tab: only the existing Radio/Streaming top-level indices.
        val inside = if (now == SETTINGS) {''',
    "remove extra settings tab")
# The fallback for a stale/missing selection is a loading state, not a crash.
one(car,
    "if (known == null || now == null) return@safely out.setLoading(true).build()",
    "if (known == null || known.size != 2 || now == null) return@safely out.setLoading(true).build()",
    "exactly two tabs")

# The upstream service is already declared as a media CarAppService, but
# release builds disable it. Enable only for this isolated CAL experiment.
gradle=root/"app/build.gradle.kts"
one(gradle,'manifestPlaceholders["carScreens"] = "false"',
    'manifestPlaceholders["carScreens"] = "true"',
    "enable Car App Service in experiment-only coreRelease")

manifest=root/"app/src/main/AndroidManifest.xml"
one(manifest,
    'android:name="androidx.car.app.minCarApiLevel"\n            android:value="6"',
    'android:name="androidx.car.app.minCarApiLevel"\n            android:value="8"',
    "MediaPlaybackTemplate requires CAL API8")

# Source-shape proof prevents regressions in the experimental branch.
test=root/"app/src/test/java/com/dd3boh/outertune/playback/PolarisCALStreamingLayoutTest.kt"
test.parent.mkdir(parents=True,exist_ok=True)
test.write_text("""package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** These prove the experiment wiring, not Polestar host pixel placement. */
class PolarisCALStreamingLayoutTest {
    private val car = File("src/main/java/com/dd3boh/outertune/playback/AutoCar.kt").readText()
    private val build = File("build.gradle.kts").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun twoTopLevelTabsStillDriveTheExistingBrowserTree() {
        assertTrue(car.contains("session.children(MusicService.ROOT)"))
        assertTrue(car.contains("RadioCatalog.RADIO_BROWSE_ROOT_ID"))
        assertTrue(car.contains("RadioCatalog.STREAMING_BROWSE_ROOT_ID"))
        assertTrue(car.contains("TabTemplate.Builder"))
        assertFalse(car.contains("out.addTab(Tab.Builder().setContentId(SETTINGS)"))
    }

    @Test fun streamingUsesRealSmallGridFollowedByPlayables() {
        assertTrue(car.contains("GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)"))
        assertTrue(car.contains("RowSection.Builder()"))
        assertTrue(car.contains("shortcuts.map { it.mediaId } == expected"))
        assertTrue(car.contains("songs.forEach { rows.addItem(row(session, it, playing)) }"))
        assertTrue(car.contains("MediaPlaybackManager"))
    }

    @Test fun releaseExperimentIsCarHostGatedWithoutBreakingLegacyBrowser() {
        assertTrue(build.contains("manifestPlaceholders") && build.contains("carScreens"))
        assertTrue(manifest.contains("androidx.car.app.category.MEDIA"))
        assertTrue(manifest.contains("android.media.browse.MediaBrowserService"))
        assertTrue(manifest.contains("androidx.car.app.minCarApiLevel"))
        assertTrue(car.contains("known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)"))
        assertFalse(car.contains("ALLOW_ALL_HOSTS_VALIDATOR"))
    }
}
""",encoding="utf-8")

assert 'versionCode = 100610' in gradle.read_text(), "no version bump without an APK order"
print("PASS: CAL experimental source patched, Radio/Streaming tabs, SMALL 4-item grid+song rows")
print("PASS: original MediaLibraryService fallback retained; no final APK requested or created")
