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
test=app/"src/androidTest/java/com/dd3boh/outertune/polaris/PolarisCAL612ManifestTest.java"
assert not test.exists()
test.write_text('''package com.dd3boh.outertune.polaris;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PolarisCAL612ManifestTest {
    @Test
    @SuppressWarnings("deprecation")
    public void installedReleaseHasCarAppTemplateAndMediaBrowserFallback() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                PackageManager.GET_SERVICES | PackageManager.GET_PERMISSIONS);
        boolean cal = false;
        boolean browser = false;
        if (info.services != null) {
            for (int i = 0; i < info.services.length; i++) {
                ServiceInfo service = info.services[i];
                if ("com.dd3boh.outertune.playback.AutoCarService".equals(service.name)) {
                    cal = service.enabled && service.exported;
                }
                if ("com.dd3boh.outertune.playback.MusicService".equals(service.name)) {
                    browser = service.enabled;
                }
            }
        }
        boolean permission = false;
        if (info.requestedPermissions != null) {
            for (int i = 0; i < info.requestedPermissions.length; i++) {
                if ("androidx.car.app.MEDIA_TEMPLATES".equals(info.requestedPermissions[i])) {
                    permission = true;
                }
            }
        }
        assertTrue("The final APK must enable and export AutoCarService", cal);
        assertTrue("Original MediaLibraryService must remain enabled", browser);
        assertTrue("The final APK must request MEDIA_TEMPLATES", permission);
    }
}
''',encoding="utf-8")

print("PASS 6.12: neutral release overlay, active CAL and media/template descriptor")
print("PASS 6.12: versionCode=100612, MediaLibrary fallback, installed-APK manifest regression test added")
