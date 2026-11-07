from pathlib import Path
import sys
r=Path(sys.argv[1]);b=r/'app/src/main/kotlin/app/hush/music';s=(b/'playback/MusicService.kt').read_text();p=b/'polaris'
bg=(b/'utils/potoken/BotGuardTokenGenerator.kt').read_text();journal=(p/'PolarisDiagnosticJournal.kt').read_text();resource=(p/'PolarisResourceDiagnostics.kt').read_text()
destroy=s[s.index('    override fun onDestroy()'):s.index('    override fun onBind(')]
checks={
 'no shutdown join or synchronous save': 'runBlocking(' not in destroy and 'saveQueueToDisk(' not in destroy and 'savePersistentUrlCache(' not in destroy,
 'healthy playback not sought on device inventory': 'PolarisAudioRoutePolicy.needsSeek(player.isPlaying' in s and 'if (!player.isPlaying) rebindAudioEffectsAfterRouteChange()' in s,
 'WebView construction cancellation closes the constructing engine': 'cont.invokeOnCancellation { engine.onMain { engine.close() } }' in bg,
 'WebView close idempotent and cancels pending': 'if (closed) return' in bg and 'pendingMints.cancelAll()' in bg and 'Thread.currentThread' not in bg,
 'completed bootstrap releases caller continuation': 'readySignal = null' in bg and 'webView.webChromeClient = object' in bg,
 'request IDs separate simultaneous mints': 'pendingMints.add(cont)' in bg and 'onMintOk("$requestId"' in bg,
 'BotGuard response always closed': 'execute().use { response ->' in bg and 'clearCache(true)' not in bg,
 'hot diagnostics paths do not use apply': all('.apply()' not in (p/f).read_text() for f in ['PolarisStreamDiagnostics.kt','PolarisRadioDiagnostics.kt','PolarisErrorReport.kt']),
 'journal uses bounded queue and one periodic writer': 'PolarisBoundedEvents()' in journal and 'scheduleWithFixedDelay' in journal and 'FILE_LIMIT = 256_000' in journal,
 'device pressure, own exits, ANR and main delay recorded': all(x in resource for x in ['totalPss','/proc/self/fd','/proc/self/task','getHistoricalProcessExitReasons','traceInputStream','main-stall','currentThermalStatus','BOOT_COUNT','권한상 수집 불가']),
 'audio pipeline diagnostics': all(x in s for x in ['onAudioUnderrun','onAudioSinkError','onAudioCodecError','audio-focus change=']),
 'metadata caches bounded': all('PolarisSmallCache<'+x+'>' in s for x in ['Long','Float','FormatEntity']),
 'short decipher retry window': '30_000L' in (b/'utils/StreamClientAvailability.kt').read_text(),
 'same app update identity': 'versionCode = 100573' in (r/'app/build.gradle.kts').read_text() and 'versionName = "50.11"' in (r/'app/build.gradle.kts').read_text(),
}
for name,ok in checks.items():print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values())
