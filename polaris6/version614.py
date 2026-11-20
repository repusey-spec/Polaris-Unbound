#!/usr/bin/env python3
"""Only version/install identity change from 6.13 to 6.14; original signer retained."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
gradle=root/"app/build.gradle.kts"
s=gradle.read_text(encoding="utf-8")
for old,new in [('versionCode = 100613','versionCode = 100614'),('versionName = "6.13"','versionName = "6.14"')]:
    assert s.count(old)==1, (old,s.count(old))
    s=s.replace(old,new,1)
gradle.write_text(s,encoding="utf-8")
test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
s=test.read_text(encoding="utf-8")
old='Polaris 6.13 (versionCode 100613)'
assert s.count(old)==1
test.write_text(s.replace(old,'Polaris 6.14 (versionCode 100614)',1),encoding="utf-8")
print("PASS Polaris 6.14 versionCode, versionName, instrumentation expected version")
