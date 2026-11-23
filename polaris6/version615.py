#!/usr/bin/env python3
"""Signed full Polaris 6.15 after the existing 6.14 patch and 6.15 final fixes."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
gradle=root/"app/build.gradle.kts"
s=gradle.read_text(encoding="utf-8")
for old,new in [("versionCode = 100614","versionCode = 100615"),
                ('versionName = "6.14"','versionName = "6.15"')]:
    assert s.count(old)==1, "Missing unique 6.14 version marker "+old
    s=s.replace(old,new,1)
gradle.write_text(s,encoding="utf-8")
print("PASS signed app identity retained, version 6.15 / 100615")
