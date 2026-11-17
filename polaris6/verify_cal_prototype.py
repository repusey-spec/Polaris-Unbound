#!/usr/bin/env python3
"""Source-level acceptance gates for isolated Car App Library branch, NOT a UI test."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
car=(pkg/"playback/AutoCar.kt").read_text()
cb=(pkg/"playback/MediaLibrarySessionCallback.kt").read_text()
manifest=(root/"app/src/main/AndroidManifest.xml").read_text()
build=(root/"app/build.gradle.kts").read_text()
radio=(pkg/"polaris/PolarisRadioBrowseTile.kt").read_text()
assert "versionCode = 100610" in build, "Do not issue a new APK or version in this experiment"
assert 'applicationId = "app.hush.music.polarisradio.debug"' in build
assert "TabTemplate.Builder(" in car
assert "val known = tabs?.take(2)" in car
assert 'setContentId(SETTINGS)' not in car
assert "GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_SMALL)" in car
assert "items.take(4).forEach { shortcuts.addItem(tile(session, it)) }" in car
assert "val songs = items.drop(4)" in car
assert "out.addSection(tracks.build())" in car
assert "tracks.addItem(row(session, it, playing))" in car
assert 'RadioCatalog.STREAMING_BROWSE_ROOT_ID' in car
assert 'RadioCatalog.RADIO_BROWSE_ROOT_ID' in cb
assert 'RadioCatalog.STREAMING_BROWSE_ROOT_ID' in cb
assert 'val lineWidth = 496' in radio, "Radio image generation is unchanged in experiment"
assert 'android:name=".playback.AutoCarService"' in manifest
assert 'android:enabled="true"' in manifest.split('android:name=".playback.AutoCarService"')[1].split("</service>",1)[0]
assert 'android:value="8"' in manifest
assert 'android.software.car.templates_host.media' in manifest and 'android:required="false"' in manifest
assert 'android.media.browse.MediaBrowserService' in manifest, "Non-CAL fallback required"
assert 'HostValidator.ALLOW_ALL_HOSTS_VALIDATOR' not in car
assert 'service is AutoCarService' in car
assert (pkg/"polaris/PolarisLastSource.kt").exists(), "Last-source restore code must remain"
print("PASS CAL prototype source: 2 top-level tabs; small 4-shortcut grid + rows; radio and legacy playback unchanged; no APK")
print("LIMIT: cannot prove portrait Android Auto 4-column layout or host CAL eligibility from compilation")
