#!/usr/bin/env python3
"""Set and verify Polaris launcher label across Android build variants."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
manifest=root/"app/src/main/AndroidManifest.xml"
s=manifest.read_text()
assert 'android:label="@string/app_name"' in s
s=s.replace('android:label="@string/app_name"', 'android:label="Polaris"')
manifest.write_text(s)
# Debug or variant resources can still define app_name; force the value in all source sets.
for p in (root/"app/src").glob("*/res/values*/strings.xml"):
    txt=p.read_text()
    import re
    txt=re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)',r'\1Polaris\2',txt)
    p.write_text(txt)
# Isolate the experiment from existing Hush Debug and stable Polaris installations.
gradle=root/"app/build.gradle.kts"
g=gradle.read_text()
needle='applicationIdSuffix = ".debug"'
assert g.count(needle)==1
g=g.replace(needle,'applicationIdSuffix = ".polarisradio.debug"')
# Preserve the project-owned debug signing identity across GitHub Actions runners.
# Without this, each new runner generates a fresh ~/.android/debug.keystore and
# Android rejects updates to an APK signed by the previous runner.
signing_anchor='''        getByName("debug") {
            enableV1Signing = true'''
assert g.count(signing_anchor)==1, "Pinned upstream Hush debug signing block changed"
g=g.replace(signing_anchor,'''        getByName("debug") {
            storeFile = rootProject.file("polaris-debug.keystore")
            storePassword = "android"
            keyAlias = "polarisdebug"
            keyPassword = "android"
            enableV1Signing = true''')
# Each CI run must have a strictly newer Android versionCode. The upstream Hush
# label/versionName stay unchanged; only this isolated experiment is affected.
version_anchor='versionCode = 177'
assert g.count(version_anchor)==1, "Pinned upstream Hush versionCode changed"
g=g.replace(version_anchor, 'versionCode = 100000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)')
gradle.write_text(g)
print("Polaris label, isolated package ID, persistent signing and increasing versionCode patched")
