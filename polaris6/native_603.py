#!/usr/bin/env python3
"""Polaris 6.03: retain native InterTune 0.12.10 streaming; keep radio at integration boundary.

Run AFTER the pinned InterTune source and original Polaris radio integration are applied.
No InterTune resolver, stream checker, token, recovery, UI destination, or media source internals are patched.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
def replace_exact(rel, pairs):
    target = root / rel
    data = target.read_text(encoding="utf-8")
    for old, new in pairs:
        count = data.count(old)
        assert count == 1, f"{rel}: expected one occurrence of {old!r}; got {count}"
        data = data.replace(old, new, 1)
    target.write_text(data, encoding="utf-8")

replace_exact("app/build.gradle.kts", [
    ("versionCode = 100600", "versionCode = 100603"),
    ('versionName = "6.0"', 'versionName = "6.03"'),
])
# New isolated data stores prevent a failed 6.0/6.01/6.02 stream from
# leaving StandIns 'song gone' or stale cache state in 6.03.
# Old files are NOT deleted or migrated. Original InterTune schemas remain intact.
replace_exact("app/src/main/java/com/dd3boh/outertune/utils/DataStore.kt", [
    ('name = "polaris6-settings"', 'name = "intertune603-settings"'),
])
replace_exact("app/src/main/java/com/dd3boh/outertune/db/MusicDatabase.kt", [
    ('DB_NAME = "polaris6-song.db"', 'DB_NAME = "intertune603-song.db"'),
])
replace_exact("app/src/main/java/com/dd3boh/outertune/di/AppModule.kt", [
    ('resolve("polaris6-exoplayer")', 'resolve("intertune603-exoplayer")'),
    ('resolve("polaris6-download")', 'resolve("intertune603-download")'),
])
print("PASS: Polaris 6.03 identity; clean, non-destructive native InterTune data namespace")
