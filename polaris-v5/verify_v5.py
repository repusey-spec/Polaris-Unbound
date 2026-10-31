#!/usr/bin/env python3
"""Regression checks for new v5, not inherited #49..57 script assertions."""
from pathlib import Path
import hashlib,sys
hush=Path(sys.argv[1])
repo=Path(__file__).resolve().parents[1]
main=hush/"app/src/main/kotlin/app/hush/music"
cb=(main/"playback/MediaLibrarySessionCallback.kt").read_text()
service=(main/"playback/MusicService.kt").read_text()
gradle=(hush/"app/build.gradle.kts").read_text()
manifest=(hush/"app/src/main/AndroidManifest.xml").read_text()
radio=(main/"polarisv5/V5Radio.kt").read_text()
checks={}
for name in ("RadioPresets","CurrentProgramResolver","StationArt"):
    dest=hush/f"app/src/main/java/com/polarisunbound/app/{name}.java"
    source=repo/f"app/src/main/java/com/polarisunbound/app/{name}.java"
    checks["v0.58 preserved "+name] = dest.read_bytes()==source.read_bytes()
checks.update({
    "pinned Hush v13.14.8":'13.14.8' not in gradle and 'versionName = "5.0.0"' in gradle,
    "separate v5 package":'applicationIdSuffix = ".polarisv5"' in gradle,
    "two AA browse roots":'V5Radio.ROOT -> V5Radio.browse(context)' in cb and 'V5Radio.STREAMING -> {' in cb,
    "native AA 13-station queue":'val radioQueue = V5Radio.queue(context)' in cb,
    "radio all getItem lookup":'V5Radio.isRadio(mediaId) -> {' in cb,
    "live station URL resolved only at source opening":'dataSpec.uri.scheme == V5Radio.SCHEME' in service,
    "do not send radio to YouTube resolver":'if (V5Radio.isRadio(mediaItem?.mediaId))' in service,
    "programme schedule background loop":'V5Radio.beginProgrammeRefresh(this)' in service,
    "steering-wheel previous and next use native timeline":'STATION_PREFIX' in radio and 'fun queue(context: Context)' in radio,
    "all 13 v0.58 stations present":all('"'+id+'"' in radio for id in ("kr1","kr2","kr3","kr4","kr5","kr6","gallery","wild949","z100","power1051","kiis","alt987","myfm1043")),
    "4x4 layout from v0.58":'SLOT_COUNT=16' in (repo/"app/src/main/java/com/polarisunbound/app/RadioPresets.java").read_text(),
    "no NAS/CAN/FTP port":not any((main/"polarisv5"/x).exists() for x in ("NasMusicStore.java","PolarisFtp.java")) and 'polaris-v5' not in service,
    "station art Android Auto provider":'V5StationArtProvider' in manifest,
    "phone radio and streaming index":'V5SourceTabs(navController, currentRoute)' in (main/"MainActivity.kt").read_text(),
    "no inherited #49..57 overlay":all("polaris-hush/" not in s for s in (cb,service,manifest)),
    "Polaris v5 launcher":'android:label="Polaris v5"' in manifest,
    "Monochrome notification icon":'polaris_v5_small' in (hush/"app/src/main/res/drawable/small_icon.xml").read_text(),
})
for name,ok in checks.items():
    print(("PASS" if ok else "FAIL")+": "+name)
assert all(checks.values()),"Polaris v5 build contract has unresolved failures"
