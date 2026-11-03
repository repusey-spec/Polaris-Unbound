#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
r=Path(sys.argv[1]); b=r/'app/src/main/kotlin/app/hush/music'
d=(b/'playback/DownloadUtil.kt').read_text(); c=(b/'playback/ChunkingDataSource.kt').read_text()
q=(b/'polaris/PolarisSerialDownloads.kt').read_text(); i=(b/'polaris/PolarisDownloadIntegrity.kt').read_text()
v=(b/'polaris/PolarisDownloadValidation.kt').read_text(); e=(b/'playback/ExoDownloadService.kt').read_text()
m=(r/'app/src/main/AndroidManifest.xml').read_text(); ET.fromstring(m)
checks={
 'one durable FIFO worker owns the full file transaction': 'serialQueue.enqueue(id, title)' in d and 'fileDownloadJobs' not in d and 'LinkedHashMap<String, Task>' in q and 'child.cancelAndJoin()' in q,
 'legacy manager parked and global resume intercepted': 'maxParallelDownloads = 1' in d and 'pauseDownloads()' in d and 'downloadUtil.resumeSerialDownloads()' in e,
 'no eager parallel download URL resolution': 'legacyDownloads.collect' not in d,
 'read errors never become normal EOF': 'catch (_: Exception)' not in c and 'log(error); throw error' in c and 'EOFException' in c,
 'actual full length compared before file commit': 'written != expected' in i and 'declaredLength != opened' in d,
 'unknown lengths are recorded honestly': 'lengthKnown=${expected > 0}' in d,
 'media validation happens before atomic replacement': d.index('PolarisDownloadValidation.validate(part, keepGoing)') < d.index('PolarisDownloadValidation.commit(part, target, keepGoing)') and 'Os.rename' in v,
 'container and complete sample walk checked': 'extractor.readSampleData' in v and 'audio ends early' in v,
 'download causes and actual local playback route reported': 'download-validation id=' in d and 'download-index-committed id=' in d and 'playback-local id=' in (b/'playback/MusicService.kt').read_text(),
 'new file foreground service declared': 'PolarisDownloadService' in m and 'foregroundServiceType="dataSync"' in m,
 'atomic queue persistence and index persistence': 'AtomicFile' in (b/'polaris/PolarisDownloadStore.kt').read_text() and 'atomic.failWrite' in (b/'downloads/DownloadedFileStore.kt').read_text(),
 '50.7 monotonic version': 'versionCode = 100569' in (r/'app/build.gradle.kts').read_text() and 'versionName = "50.7"' in (r/'app/build.gradle.kts').read_text(),
 '50.6 search radio and track isolation preserved': (b/'polaris/PolarisCarScreens.kt').exists() and (b/'polaris/PolarisPlaybackJobs.kt').exists() and 'PolarisRadioRequests.resolve(spec)' in (b/'playback/MusicService.kt').read_text(),
}
for name, ok in checks.items(): print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values())
