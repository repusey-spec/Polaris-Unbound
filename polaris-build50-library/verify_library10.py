from pathlib import Path
import sys
r=Path(sys.argv[1]); b=r/'app/src/main/kotlin/app/hush/music'; c=r/'core/src/main/kotlin/app/hush/music/innertube'
i=(c/'InnerTube.kt').read_text(); y=(b/'utils/YTPlayerUtils.kt').read_text(); ctrl=(c/'StreamResolutionControl.kt').read_text()
retry=i[i.index('    suspend fun player('):i.index('    private suspend fun executePlayerRequest(')]
checks={
 'body-only context retry retains caller authentication': 'setLogin = false' not in retry and 'includeDataSyncId = false' in retry,
 'cancellation rethrown before fallback': 'StreamResolutionControl.rethrowCancellation(failure)' in retry and 'if (error is CancellationException) throw error' in ctrl,
 'quality and download Result boundaries cancel cooperatively': y.count('StreamResolutionControl.attempt {')>=4 and y.count('currentCoroutineContext().ensureActive()')>=7,
 'signature bootstrap and conversion interruptible': 'runInterruptible(Dispatchers.IO) { getSignatureTimestampBlocking' in y and 'runInterruptible(Dispatchers.IO) {\n                NewPipeUtils.getStreamUrl' in y,
 'candidate reasons separate GVS, cooldown and conversion': all(x in y for x in ['missing-gvs-token','decipher-cooldown','conversion-start','conversion-failed','conversion-ready']),
 'local failure survives later login refusal': y.index('localCandidateFailure?.let { throw it }')<y.index('            gateFailure?.let { failure ->'),
 'diagnostics excludes exception message': 'error.message' not in ctrl and 'PlaybackTrace.label(it.javaClass.simpleName)' in ctrl,
 'local missing still falls through to shared stream resolution': 'reason=no-usable-local-file-or-cache' in (b/'playback/MusicService.kt').read_text(),
 'report and update identity': 'DIAGNOSTICS (50.10)' in (b/'polaris/PolarisErrorReport.kt').read_text() and 'versionCode = 100572' in (r/'app/build.gradle.kts').read_text() and 'versionName = "50.10"' in (r/'app/build.gradle.kts').read_text(),
}
for name,ok in checks.items():print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values())
