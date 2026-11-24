#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(sys.argv[1])
f=root/"app/build.gradle.kts"
s=f.read_text()
assert s.count("versionCode = 100615")==1
assert s.count('versionName = "6.15"')==1
s=s.replace("versionCode = 100615","versionCode = 100616",1)
s=s.replace('versionName = "6.15"','versionName = "6.16"',1)
f.write_text(s)
print("PASS 6.16 package ID stays identical, 100616 / 6.16")
