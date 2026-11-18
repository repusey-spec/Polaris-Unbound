#!/usr/bin/env python3
"""Fail the 6.03 build if streaming diverges from pinned InterTune v0.12.10."""
from pathlib import Path
import sys, subprocess, hashlib

p = Path(sys.argv[1]).resolve()
s = p / "app/src/main/java/com/dd3boh/outertune"
gradle = (p / "app/build.gradle.kts").read_text()

assert 'versionCode = 100603' in gradle
assert 'versionName = "6.03"' in gradle
assert 'applicationId = "app.hush.music.polarisradio.debug"' in gradle

# Byte-for-byte identity with the original InterTune v0.12.10 checkout.
original_paths = [
    "utils/YTPlayerUtils.kt",
    "utils/StreamCheck.kt",
    "utils/StreamOrder.kt",
    "utils/StreamFamily.kt",
    "utils/potoken/PoTokenGenerator.kt",
    "playback/StandIns.kt",
    "playback/StreamAddresses.kt",
    "playback/BadCachedCopy.kt",
]
for name in original_paths:
    path = s / name
    assert path.is_file(), f"Required native InterTune component missing: {name}"
    rel = str(path.relative_to(p))
    original = subprocess.check_output(["git", "-C", str(p), "show", "HEAD:" + rel])
    modified = path.read_bytes()
    assert original == modified, f"Native InterTune 0.12.10 altered: {rel}"
    print(f"IDENTICAL {name} SHA256 {hashlib.sha256(modified).hexdigest()[:16]}")

# Optional old module path; if it exists, it must also match upstream.
optional = s / "playback/SongResolver.kt"
if optional.is_file():
    original = subprocess.check_output(["git", "-C", str(p), "show", "HEAD:" + str(optional.relative_to(p))])
    assert original == optional.read_bytes(), "Original SongResolver modified"

assert "intertune603-song.db" in (s / "db/MusicDatabase.kt").read_text()
assert "intertune603-settings" in (s / "utils/DataStore.kt").read_text()
cache = (s / "di/AppModule.kt").read_text()
assert "intertune603-exoplayer" in cache and "intertune603-download" in cache

# Integration must dispatch songs to the original InterTune factory, not a
# new radio HTTP data source, resolver, or forked YouTube implementation.
media = (s / "polaris/PolarisMediaSources.kt").read_text()
assert "else songs.createMediaSource(mediaItem)" in media
assert "PolarisRadioRequests.stationId(mediaItem) != null" in media
service = (s / "playback/MusicService.kt").read_text()
assert "PolarisMediaSources(this, DefaultMediaSourceFactory(createDataSourceFactory()))" in service

activity = (s / "MainActivity.kt").read_text()
assert "PolarisTopTabs(polarisRadioTab)" in activity
assert 'composable("polaris_radio")' in activity
assert "appDestinations" in activity
session = (s / "playback/MediaLibrarySessionCallback.kt").read_text()
assert "PolarisVoiceRouter" in session
assert "RadioCatalog.STREAMING_ROOT_ID" in session
assert "AUDIO-UNDERRUN" in (s / "polaris/PolarisRadioSession.kt").read_text()

# 6.0.1's rejected-stream patch and 6.02 modifications are NOT the base.
assert "rescueDeniedWebHead" not in (s / "utils/YTPlayerUtils.kt").read_text()
assert "rescueDeniedWebHead" not in (s / "utils/StreamCheck.kt").read_text()
print("PASS: 6.03 identity, pristine native InterTune streaming core, native UI, radio boundary, clean state")
