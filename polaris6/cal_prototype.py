#!/usr/bin/env python3
"""Isolated Car App Library experiment on top of 6.10. NO APK/version change.

Use InterTune's existing AutoCarService/TabTemplate and its existing section
renderer. Keep the MediaLibraryService fallback untouched for AA hosts without
the CAL media template capability. Enable ONLY AutoCarService (not Settings).
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
src=root/"app/src/main"
path=src/"java/com/dd3boh/outertune/playback/AutoCar.kt"
s=path.read_text(encoding="utf-8")

def swap(old,new,name):
    global s
    n=s.count(old)
    if n!=1:
        raise AssertionError(f"CAL prototype {name}: expected one match, got {n}")
    s=s.replace(old,new,1)

# Without this branch, the RELEASE build's AUTO_REWORK=false permits no
# configured media hosts at all. Limit the allowlist to AndroidX's published
# certificate sample for the car template service only. Never allow all hosts.
swap(
'''    if (Unreleased.AUTO_REWORK) known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)''',
'''    if (Unreleased.AUTO_REWORK || service is AutoCarService)
        known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)''',
"trusted media template hosts"
)

# The native SectionedItemTemplate can explicitly represent two unlike
# sections: SMALL browse icons and a conventional song RowSection.
# Parent is a browse tab, not one of the root's four original InterTune tabs.
anchor='''    val playing = session.player?.currentMediaItem?.mediaId
    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {'''
block='''    val playing = session.player?.currentMediaItem?.mediaId

    // Polaris Streaming is a top-level TabTemplate tab whose contents mix
    // exactly four browsing shortcuts and a playable list. This is NOT
    // achievable by rearranging MediaBrowser content-style hints alone.
    val isStreaming = list != null && list.mediaId in setOf(
        com.dd3boh.outertune.polaris.RadioCatalog.STREAMING_BROWSE_ROOT_ID,
        com.dd3boh.outertune.polaris.RadioCatalog.STREAMING_BROWSE_PREVIOUS_ROOT_ID,
        com.dd3boh.outertune.polaris.RadioCatalog.STREAMING_ROOT_ID,
    )
    if (isStreaming && items.size >= 4 &&
        items.take(4).all { it.mediaMetadata.isBrowsable == true }) {
        val shortcuts = GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)
        items.take(4).forEach { shortcuts.addItem(tile(session, it)) }
        out.addSection(shortcuts.build())

        val songs = items.drop(4)
        if (songs.isNotEmpty()) {
            val tracks = RowSection.Builder()
            songs.forEach { tracks.addItem(row(session, it, playing)) }
            out.addSection(tracks.build())
        }
        return out.build()
    }

    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {'''
swap(anchor,block,"small grid above playable songs")

# Radio remains an independent tab with its 6.10 artwork; streaming shares
# the same existing session but no longer adds an unwanted third Settings tab.
swap("val known = tabs?.take(3)","val known = tabs?.take(2)","only two top-level tabs")
swap('''        out.addTab(Tab.Builder().setContentId(SETTINGS).setTitle(carContext.getString(R.string.settings)).setIcon(carContext.icon(R.drawable.auto_settings)).build())
        val inside = if (now == SETTINGS) {
            val list = ListTemplate.Builder()
            corner(session).forEach(list::addAction)
            settings?.let { now -> settingsSections(carContext, now, screenManager).forEach { list.addSectionedList(it) } } ?: list.setLoading(true)
            list.build()
        } else {
            sectioned(session, known.firstOrNull { it.mediaId == now }, lists[now], header = null)
        }''',
'''        val inside = sectioned(session,
            known.firstOrNull { it.mediaId == now }, lists[now], header = null)''',
"no third Settings tab"
)
path.write_text(s,encoding="utf-8")

manifest=src/"AndroidManifest.xml"
m=manifest.read_text(encoding="utf-8")
old='''android:name=".playback.AutoCarService"
            android:enabled="${carScreens}"'''
new='''android:name=".playback.AutoCarService"
            android:enabled="true"'''
assert m.count(old)==1,"CAL service manifest entry changed"
m=m.replace(old,new,1)
old='''android:name="androidx.car.app.minCarApiLevel"
            android:value="6"'''
new='''android:name="androidx.car.app.minCarApiLevel"
            android:value="8"'''
assert m.count(old)==1,"CAL media needs API level 8"
m=m.replace(old,new,1)
# Required=false: app remains visible/installable on vehicles without
# the template-based media host; the original MediaLibraryService stays active.
needle='''    <uses-permission android:name="androidx.car.app.MEDIA_TEMPLATES" />'''
assert m.count(needle)==1,"CAL permission marker"
m=m.replace(needle,needle+'''
    <uses-feature android:name="android.software.car.templates_host.media"
        android:required="false" />''',1)
manifest.write_text(m,encoding="utf-8")

# Source-only test inspects the *transformed* tree. No APK is constructed.
unit=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisCALPrototypeTest.kt"
unit.write_text("""package com.dd3boh.outertune.polaris

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class PolarisCALPrototypeTest {
    private val car = File("src/main/java/com/dd3boh/outertune/playback/AutoCar.kt").readText()
    private val cb = File("src/main/java/com/dd3boh/outertune/playback/MediaLibrarySessionCallback.kt").readText()
    private val xml = File("src/main/AndroidManifest.xml").readText()

    @Test fun radioAndStreamingAreOnlyTwoTopLevelTabs() {
        assertTrue(car.contains("val known = tabs?.take(2)"))
        assertFalse(car.contains("out.addTab(Tab.Builder().setContentId(SETTINGS)"))
        assertTrue(car.contains("TabTemplate.Builder("))
        assertTrue(cb.contains("polarisFolder(RadioCatalog.RADIO_BROWSE_ROOT_ID"))
        assertTrue(cb.contains("polarisFolder(RadioCatalog.STREAMING_BROWSE_ROOT_ID"))
    }

    @Test fun streamingRendersCompactCategoriesAndSongsTogether() {
        assertTrue(car.contains("GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)"))
        assertTrue(car.contains("items.take(4).forEach { shortcuts.addItem(tile(session, it)) }"))
        assertTrue(car.contains("val songs = items.drop(4)"))
        assertTrue(car.contains("tracks.addItem(row(session, it, playing))"))
        assertTrue(car.contains("out.addSection(tracks.build())"))
    }

    @Test fun supportedHostGetsTemplateButOlderHostsKeepMediaBrowser() {
        assertTrue(xml.contains(".playback.AutoCarService"))
        assertTrue(xml.contains("androidx.car.app.minCarApiLevel"))
        assertTrue(xml.contains("android:value=\\\"8\\\""))
        assertTrue(xml.contains("android.software.car.templates_host.media"))
        assertTrue(xml.contains("android:required=\\\"false\\\""))
        assertTrue(xml.contains("android.media.browse.MediaBrowserService"))
        assertTrue(car.contains("service is AutoCarService"))
        assertFalse(car.contains("HostValidator.ALLOW_ALL_HOSTS_VALIDATOR"))
    }
}
""",encoding="utf-8")
print("CAL prototype source injected: Radio/Streaming tabs + SMALL category grid + songs rows + old MediaBrowser fallback; no APK")
