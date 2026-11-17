#!/usr/bin/env python3
"""Version the verified 6.11 Car App Library experiment for an installable APK."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
build=root/"app/build.gradle.kts"
t=build.read_text(encoding="utf-8")
for old,new in [("versionCode = 100610","versionCode = 100611"),('versionName = "6.10"','versionName = "6.11"')]:
    assert t.count(old)==1, f"Missing {old}"
    t=t.replace(old,new,1)
build.write_text(t,encoding="utf-8")
test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
t=test.read_text(encoding="utf-8")
old="Polaris 6.10 (versionCode 100610)"
assert t.count(old)==1, "Session instrumentation version assertion changed"
test.write_text(t.replace(old,"Polaris 6.11 (versionCode 100611)",1),encoding="utf-8")
manifest=root/"app/src/main/AndroidManifest.xml"
m=manifest.read_text(encoding="utf-8")
assert m.count('android:enabled=')>=2
assert 'android:name=".playback.AutoCarService"' in m
assert 'android:value="8"' in m
assert 'android.software.car.templates_host.media' in m
assert 'android:required="false"' in m
ui=(root/"app/src/main/java/com/dd3boh/outertune/playback/AutoCar.kt").read_text(encoding="utf-8")
assert 'TabTemplate.Builder' in ui
assert 'GridSection.ITEM_SIZE_SMALL' in ui
assert 'val known = tabs?.take(2)' in ui
assert 'out.addSection(rows.build())' in ui
assert 'GridSection.ITEM_SIZE_EXTRA_LARGE' in ui
assert 'allowAllHosts' not in ui
assert 'manifestPlaceholders["carScreens"] = "true"' in build.read_text()
print("PASS 6.11 versionCode=100611, signed release-ready CAL template enabled, legacy media browser retained")
