#!/usr/bin/env python3
"""Verify 6.11 CAL prototype wiring, without generating any APK."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune"
ui=(src/"playback/AutoCar.kt").read_text(encoding="utf-8")
callback=(src/"playback/MediaLibrarySessionCallback.kt").read_text(encoding="utf-8")
catalog=(src/"polaris/RadioCatalog.kt").read_text(encoding="utf-8")
manifest=(root/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
build=(root/"app/build.gradle.kts").read_text(encoding="utf-8")
for expected in (
    'val known = tabs?.take(2)',
    'if (list?.mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID)',
    'GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)',
    'val songs = items.drop(4)',
    'smallIcons.addItem(tile(session, it))',
    'rows.addItem(row(session, it, playing))',
    'out.addSection(rows.build())',
    'TabTemplate.Builder(object : TabTemplate.TabCallback',
    'known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)',
):
    assert expected in ui, expected
assert 'GridSection.ITEM_SIZE_EXTRA_LARGE' in ui, 'Keep existing large radio logos'
assert 'out.addTab(Tab.Builder().setContentId(SETTINGS)' not in ui
assert 'android:value="8"' in manifest
assert 'android.software.car.templates_host.media' in manifest
assert 'android:required="false"' in manifest
assert 'manifestPlaceholders["carScreens"] = "true"' in build
assert 'versionCode = 100610' in build, 'No new APK/version was authorized'
assert 'polaris:radio:v610' in catalog and 'polaris:streaming:v610' in catalog
assert 'RadioCatalog.STREAMING_BROWSE_ROOT_ID,' in callback
assert 'polarisStreamingHome(page, pageSize)' in callback
assert 'PolarisRadioBrowseTile.render' in (src/"polaris/PolarisRadioArtworkProvider.kt").read_text()
print("PASS CAL 6.11 prototype: 2 top tabs, small 4 icon grid, rows, kept radio logos")
print("PASS CAL 6.11 prototype: release host signature allowlist, fallback media browser, unchanged 6.10 identity")
