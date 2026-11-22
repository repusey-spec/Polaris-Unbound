#!/usr/bin/env python3
"""Validate the 6.15 AA-first startup-gate patch AFTER aa615.py."""
from pathlib import Path
import sys
s=(Path(sys.argv[1])/"app/src/main/java/com/dd3boh/outertune/playback/MusicService.kt").read_text(encoding="utf-8")
checks={
"old 2.5-second value": "polarisAaStartDelayMs = 2500L" in s,
"projection observed": "polarisWaitForAaProjection()" in s and "CONNECTION_TYPE_PROJECTION" in s,
"boot-to-AA silence": "if (!polarisAaStartReady()) return" in s,
"only one automatic gate": s.count("if (!polarisAaStartReady()) return")==1,
"recheck projection": "carType.value != projection" in s,
"do not override play/pause": "polarisAaStartupUserIntervened" in s,
"existing radio resume remains": "Resume on launch: last source radio=" in s,
"existing streaming resume remains": "Resuming playback on launch" in s
}
for label,ok in checks.items():print(("PASS" if ok else "FAIL"),label)
assert all(checks.values()),"6.15 AA startup static regression failed"
