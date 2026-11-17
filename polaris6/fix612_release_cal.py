#!/usr/bin/env python3
"""6.12 repair the RELEASE manifest/resource overlays that silently stripped 6.11 CAL.

The source and Kotlin compilation were correct in 6.11. The release overlay's
tools:node="remove" erased AutoCarService, MEDIA_TEMPLATES and the API level,
and release automotive_app_desc.xml overwrote main's template declaration.
Preserve the existing MediaLibraryService fallback; no changes to playback.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
app=root/"app"
release_manifest=app/"src/release/AndroidManifest.xml"
m=release_manifest.read_text(encoding="utf-8")
expected=('AutoCarService','AutoSettingsService','AutoSettingsReceiver','androidx.car.app.MEDIA_TEMPLATES','androidx.car.app.minCarApiLevel')
for item in expected:
    assert item in m, f"Expected 6.11 release suppressor missing: {item}"
assert m.count('tools:node="remove"') >= 5
# This overlay had no other release-specific declarations; leaving it as a
# neutral manifest allows the complete main manifest through normal merging.
release_manifest.write_text('''<?xml version="1.0" encoding="utf-8"?>
<!-- Polaris 6.12: release CAL is enabled. Do not delete main's
     AutoCarService, MEDIA_TEMPLATES permission, minCarApiLevel or receivers.
     The standard MediaLibraryService also remains for older Android Auto. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application />
</manifest>
''',encoding="utf-8")
release_desc=app/"src/release/res/xml/automotive_app_desc.xml"
d=release_desc.read_text(encoding="utf-8")
assert '<uses name="media" />' in d and '<uses name="template" />' not in d, "Unexpected old release descriptor"
release_desc.write_text('''<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media" />
    <uses name="template" />
</automotiveApp>
''',encoding="utf-8")
main=(app/"src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for item in expected:
    assert item in main, f"main manifest missing {item}"
assert 'android:value="8"' in main
assert 'android.software.car.templates_host.media' in main
assert 'android:required="false"' in main
gradle=app/"build.gradle.kts"
s=gradle.read_text(encoding="utf-8")
for a,b in [('versionCode = 100611','versionCode = 100612'),('versionName = "6.11"','versionName = "6.12"')]:
    assert s.count(a)==1, f"Expected prior version missing: {a}"
    s=s.replace(a,b,1)
assert 'manifestPlaceholders["carScreens"] = "true"' in s
gradle.write_text(s,encoding="utf-8")
t=app/"src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
s=t.read_text(encoding="utf-8")
before="Polaris 6.11 (versionCode 100611)"
assert s.count(before)==1
t.write_text(s.replace(before,"Polaris 6.12 (versionCode 100612)",1),encoding="utf-8")

# Dynamic check of the *installed* release APK, not the source tree.
# Five existing runtime tests + one package-manager manifest regression.
test=app/"src/androidTest/java/com/dd3boh/outertune/polaris/PolarisCAL612ManifestTest.kt"
assert not test.exists()
test.write_text('''package com.dd3boh.outertune.polaris

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolarisCAL612ManifestTest {
    @Test fun installedReleaseHasCarAppTemplateAndMediaBrowserFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
        )
        val services = info.services.orEmpty().map { it.name }
        assertTrue("Release must declare its CAL media service",
            services.any { it == "com.dd3boh.outertune.playback.AutoCarService" })
        assertTrue("The normal MediaLibraryService must remain available",
            services.any { it == "com.dd3boh.outertune.playback.MusicService" })
        assertTrue("Release must request CAL media template permission",
            info.requestedPermissions.orEmpty().contains("androidx.car.app.MEDIA_TEMPLATES"))
        val cal = info.services.orEmpty().first { it.name.endsWith(".AutoCarService") }
        assertTrue("CAL media service must be enabled", cal.enabled)
        assertTrue("CAL media service must be exported to the host", cal.exported)
    }
}
''',encoding="utf-8")
print("PASS 6.12: neutral release overlay, active CAL and media/template descriptor")
print("PASS 6.12: versionCode=100612, MediaLibrary fallback, installed-APK manifest regression test added")
