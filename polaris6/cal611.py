#!/usr/bin/env python3
"""Experimental Car App Library media-template frontend for the 6.10 playback tree.

No changes to radio/streaming sources, user settings, DB, voice, station IDs or
MediaLibrary fallback. No APK is built by this script. Host support is a separate
Android Auto beta compatibility question.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune"
autocar=src/"playback/AutoCar.kt"
manifest=root/"app/src/main/AndroidManifest.xml"
gradle=root/"app/build.gradle.kts"
def update(path, before, after, description):
    text=path.read_text(encoding="utf-8")
    count=text.count(before)
    assert count==1, f"{path.name} {description}: expected once, saw {count}"
    path.write_text(text.replace(before,after,1),encoding="utf-8")

update(autocar,
       'import androidx.car.app.versioning.CarAppApiLevels\n',
       'import androidx.car.app.versioning.CarAppApiLevels\n'
       'import com.dd3boh.outertune.polaris.RadioCatalog\n',
       "Import Polaris stable radio/streaming browser IDs")

update(autocar,
       '    if (Unreleased.AUTO_REWORK) known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)',
       '    // Signed Android Auto hosts must be allowlisted in release, too.\n'
       '    // Do not enable ALLOW_ALL_HOSTS_VALIDATOR on a car-connected app.\n'
       '    known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)',
       "Enable signature-restricted trusted Android Auto hosts for CAL experiment")

# Car App Library's SectionedItemTemplate can deliberately show a small icon
# grid followed by ordinary song rows. Its sections are not the nested media
# browser's host-chosen item styles (which produced 2x2 or four vertical rows).
anchor='''    val playing = session.player?.currentMediaItem?.mediaId
    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {'''
replacement='''    val playing = session.player?.currentMediaItem?.mediaId
    if (list?.mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID) {
        // Streaming has exactly four shortcut categories, then playable songs.
        // These are the same MediaLibrary items; no duplicate queue/play logic.
        val categories = items.take(4)
        val songs = items.drop(4)
        if (categories.isNotEmpty()) {
            val smallIcons = GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)
            categories.forEach { smallIcons.addItem(tile(session, it)) }
            out.addSection(smallIcons.build())
        }
        if (songs.isNotEmpty()) {
            val rows = RowSection.Builder()
            songs.forEach { rows.addItem(row(session, it, playing)) }
            out.addSection(rows.build())
        }
        return out.build()
    }
    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {'''
update(autocar,anchor,replacement,"Small categories GridSection plus RowSection within Streaming")

# The original three library tabs plus Settings becomes precisely two app tabs.
# Deliberately do not modify the fallback MediaLibrary roots, only the custom
# template UI. Existing settings are available in the phone app.
update(autocar,'        val known = tabs?.take(3)',
       '        val known = tabs?.take(2)',"Exactly Radio and Streaming top tabs")
update(autocar,
       '''        out.addTab(Tab.Builder().setContentId(SETTINGS).setTitle(carContext.getString(R.string.settings)).setIcon(carContext.icon(R.drawable.auto_settings)).build())
        val inside = if (now == SETTINGS) {''',
       '''        // No extra top-level Settings tab: preserve the requested two-tab layout.
        val inside = if (now == SETTINGS) {''',
       "Do not add settings as third top-level tab")

# Official CAL media guides require API level >=8 for MediaPlaybackTemplate.
update(manifest,
       '''            android:name="androidx.car.app.minCarApiLevel"
            android:value="6"''',
       '''            android:name="androidx.car.app.minCarApiLevel"
            android:value="8"''',
       "Require CAL host API level 8 for MediaPlaybackTemplate")

# For single-APK automotive compatibility the CAL host feature must be optional
# so the unchanged MediaLibraryService remains available as a fallback.
update(manifest,
       '''    <uses-permission android:name="androidx.car.app.MEDIA_TEMPLATES" />''',
       '''    <uses-permission android:name="androidx.car.app.MEDIA_TEMPLATES" />
    <uses-feature android:name="android.software.car.templates_host.media"
        android:required="false" />''',
       "Keep legacy car media hosts installable")

update(gradle,
       '''manifestPlaceholders["carScreens"] = "false"''',
       '''manifestPlaceholders["carScreens"] = "true"''',
       "Enable the existing templated media service only in the experimental source")

print("PASS 6.11 CAL prototype source preparation")
print("New CAL frontend: Radio + Streaming TabTemplate, Streaming small icon grid + playable song list")
print("No APK assembled; Android Auto host opt-in still required")
