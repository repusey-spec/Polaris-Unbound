from pathlib import Path
import sys
r=Path(sys.argv[1]);b=r/'app/src/main/kotlin/app/hush/music';c=r/'core/src/main/kotlin/app/hush/music/innertube'
s=(c/'PlaybackTrace.kt').read_text();i=(c/'InnerTube.kt').read_text();y=(c/'YouTube.kt').read_text();p=(b/'playback/MusicService.kt').read_text();d=(b/'utils/YTPlayerUtils.kt').read_text()
checks={
 'actual built headers observed as booleans': 'headers["Authorization"].isNullOrBlank()' in i and 'dataSyncBody=$includeDataSyncId' in i,
 'HTTP response and decode/playability separate': 'http=${response.status.value}' in i and 'PlaybackTrace.response(request, videoId' in y,
 'requested versus returned ID correlated': 'idMatch=' in s and 'response.videoDetails?.videoId' in y,
 'auth snapshots compared without persistent fingerprints': 'authMatchesGlobal=' in s and 'authEpoch=' in s and 'authFingerprint=' not in s,
 'download purpose and original ID propagate': 'PlaybackTrace.resolution(videoId, "download")' in d and 'return@resolution attemptResult.getOrThrow()' in d,
 'local missing transitions to stream resolver': 'reason=no-usable-local-file-or-cache' in p and 'resolution-local' in p,
 'server free text categorized instead of persisted': 'other-server-reason' in s and '${reason}' not in s,
 'diagnostic errors isolated': 'catch (_: Exception)' in s,
 'report has independent bounded stream ring': 'PolarisStreamDiagnostics.dump(context)' in (b/'polaris/PolarisErrorReport.kt').read_text() and '72_000' in (b/'polaris/PolarisStreamDiagnostics.kt').read_text(),
 'bootstrap and probe phases included': all(x in d for x in ['signature-start','metadata-start','stream-clients','url-probe']),
 'update identity retained': 'versionCode = 100571' in (r/'app/build.gradle.kts').read_text() and 'versionName = "50.9"' in (r/'app/build.gradle.kts').read_text(),
}
for name,ok in checks.items():print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values())
