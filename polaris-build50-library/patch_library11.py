#!/usr/bin/env python3
"""50.11: bounded diagnostics, owned WebViews, nonblocking teardown and conservative audio recovery."""
from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]);here=Path(__file__).resolve().parent;b=r/'app/src/main/kotlin/app/hush/music'; c=r/'core/src/main/kotlin/app/hush/music/innertube'
def once(s,old,new,label):
    assert s.count(old)==1,(label,s.count(old));return s.replace(old,new,1)
for f in (here/'resources11').glob('*.kt'):
    if f.name.endswith('Test.kt'):
        dest=r/'app/src/test/kotlin/app/hush/music/polaris'/f.name
    elif f.name=='BotGuardTokenGenerator.kt': dest=b/'utils/potoken'/f.name
    else: dest=b/'polaris'/f.name
    shutil.copyfile(f,dest)
# Replace preference writes on event paths, retain report entry points and phone sharing UI.
p=b/'polaris/PolarisErrorReport.kt';s=p.read_text()
a=s.index('    @Synchronized fun record(');z=s.index('    fun connection(',a)
s=s[:a]+'''    fun record(event: String) = PolarisDiagnosticJournal.record("playback", event)

'''+s[z:]
s=s.replace('''fun connection(state: Int) { aaState = state.toString(); record("AA projection-state=$state") }''','''fun connection(state: Int) { aaState = state.toString(); PolarisResourceDiagnostics.projection(state); record("AA projection-state=$state") }''')
s=once(s,'        app = context.applicationContext','        app = context.applicationContext\n        PolarisDiagnosticJournal.install(context)','journal install')
s=once(s,'''            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("last-error", safe)?.putInt("last-error-build", BuildConfig.VERSION_CODE)?.apply()''','''            PolarisDiagnosticJournal.rememberError(safe)''','last error')
s=s.replace('DIAGNOSTICS (50.10)','DIAGNOSTICS (50.11)')
s=s.replace('''            val lastError = prefs.getString("last-error", null)''','''            val lastError = PolarisDiagnosticJournal.lastError() ?: prefs.getString("last-error", null)''')
s=s.replace('''if (lastError != null && prefs.getInt("last-error-build", -1) != BuildConfig.VERSION_CODE)''','''if (lastError != null && !lastError.contains("code=${BuildConfig.VERSION_CODE}"))''')
s=s.replace('''            appendLine(prefs.getString("events", "아직 기록된 이벤트가 없습니다."))''','''            appendLine(PolarisDiagnosticJournal.dump("playback"))
            appendLine("=== PROCESS / DEVICE RESOURCE DIAGNOSTICS ===")
            appendLine(PolarisResourceDiagnostics.dump())''')
# Read and format files off Main, cancel delivery when the report activity is destroyed.
s=s.replace('''class PolarisReportActivity : Activity() {
    override''','''class PolarisReportActivity : Activity() {
    private val reportScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    override fun onDestroy() { reportScope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }
    override''')
s=s.replace('''        val report = PolarisErrorReport.dump(this)
        val column''','''        val loading = TextView(this).apply { text = "진단 리포트를 읽고 있습니다…"; setPadding(20, 24, 20, 20) }
        setContentView(loading)
        reportScope.launch {
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PolarisErrorReport.dump(this@PolarisReportActivity) }
            showReport(report)
        }
    }
    private fun showReport(report: String) {
        val column''')
s=s.replace('import android.app.Activity','import kotlinx.coroutines.launch\nimport android.app.Activity',1)
p.write_text(s)
# App resources observer is process-owned; no observer or poller is created per AA connection.
p=b/'App.kt';s=p.read_text();s=once(s,'        BotGuardTokenGenerator.initialize(this)','''        BotGuardTokenGenerator.initialize(this)
        app.hush.music.polaris.PolarisResourceDiagnostics.install(this)''','resource install')
s=once(s,'        val action = memoryTrimActionFor(level)','''        app.hush.music.polaris.PolarisResourceDiagnostics.trim(level)
        val action = memoryTrimActionFor(level)''','trim')
s=once(s,'        super.onLowMemory()','''        super.onLowMemory()
        app.hush.music.polaris.PolarisResourceDiagnostics.trim(-1)''','low memory')
s=once(s,'            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->','''            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                app.hush.music.polaris.PolarisResourceDiagnostics.fatal(thread, throwable)''','crash capture');p.write_text(s)
p=b/'playback/MediaLibrarySessionCallback.kt';s=p.read_text();s=once(s,'''            items.forEach { app.hush.music.polaris.PolarisErrorReport.record("set-ready " + app.hush.music.polaris.PolarisErrorReport.item(it)) }''','''            app.hush.music.polaris.PolarisErrorReport.record("set-ready queue=" + items.size + " selected=" + index + " " + app.hush.music.polaris.PolarisErrorReport.item(items.getOrNull(index.coerceAtLeast(0))))''','queue summary');p.write_text(s)
p=b/'polaris/PolarisSourceFactory.kt';s=p.read_text();s=s.replace('    override fun createMediaSource','    private var created = 0\n    override fun createMediaSource',1);s=s.replace('''        PolarisErrorReport.record("source-create route=''', '''        if (++created <= 3 || created % 50 == 0) PolarisErrorReport.record("source-create count=$created route=''');p.write_text(s)
p=b/'playback/MusicService.kt';s=p.read_text()
s=once(s,'        polarisProjectionRecovery.onAudioRouteChanged()','''        polarisProjectionRecovery.onAudioRouteChanged()
        app.hush.music.polaris.PolarisResourceDiagnostics.event("audio-devices signature=" + outputSignature.take(300) + " playing=" + player.isPlaying + " state=" + player.playbackState)''','audio devices')
s=once(s,'        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)\n        player.setAudioAttributes(playbackAudioAttributes(), false)','''        // Do not reset a healthy local stream on a change to an inactive output device.
        if (!player.isPlaying) cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)''','healthy route')
s=once(s,'        if (!shouldRebuildPlaybackForAudioRouteChange()) return','''        if (!app.hush.music.polaris.PolarisAudioRoutePolicy.needsSeek(player.isPlaying, player.playWhenReady, player.playbackState == Player.STATE_BUFFERING, player.playbackState == Player.STATE_IDLE)) {
            app.hush.music.polaris.PolarisResourceDiagnostics.event("audio-route decision=keep-playing")
            return
        }''','route gating')
s=once(s,'        player.playWhenReady = false\n        player.seekTo(mediaItemIndex, playbackPosition)','''        app.hush.music.polaris.PolarisResourceDiagnostics.event("audio-route decision=recover-buffering index=$mediaItemIndex position=$playbackPosition")
        player.playWhenReady = false
        player.seekTo(mediaItemIndex, playbackPosition)''','route action')
s=once(s,'    private fun handleAudioFocusChange(focusChange: Int) {','''    private fun handleAudioFocusChange(focusChange: Int) {
        app.hush.music.polaris.PolarisResourceDiagnostics.event("audio-focus change=$focusChange")''','focus event')
# Close/reopen effects only if playback is stalled. Output inventory alone is not a sound failure.
s=once(s,'        rebindAudioEffectsAfterRouteChange()','''        if (!player.isPlaying) rebindAudioEffectsAfterRouteChange()''','effect gating')
# Avoid serializing on service shutdown. Existing debounced track/queue saves and periodic saves remain.
a=s.index('        // Use runCatching + runBlocking with a short timeout.',s.index('    override fun onDestroy()'))
z=s.index('        effectiveVolumeRampJob?.cancel()',a)
s=s[:a]+'''        app.hush.music.polaris.PolarisResourceDiagnostics.player("service=destroying")
        // Periodic/debounced persistence owns snapshots; teardown never joins file/Binder work.
'''+s[z:]
a=s.index('                if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {',s.index('    override fun onDestroy()'))
z=s.index('\n            } catch (_: Exception)',a)
s=s[:a]+'''                // Queue changes are persisted while alive; no runBlocking in lifecycle callbacks.'''+s[z:]
s=once(s,'        scopeJob.cancel()\n    }\n\n    override fun onBind','''        scopeJob.cancel()
        app.hush.music.polaris.PolarisResourceDiagnostics.player("service=destroyed")
    }

    override fun onBind''','teardown end')
s=once(s,'        loadPersistentUrlCache()\n        // The persistent cache','''        ioScope.launch {
            loadPersistentUrlCache()
            purgeCacheEntriesViolatingSourceToggles()
        }
        // The persistent cache''','background cache restore')
s=once(s,'    private fun loadPersistentUrlCache() {','''    private fun loadPersistentUrlCache() {
        val generation = persistentSaveGeneration.get()''','cache restore generation')
s=once(s,'            if (entry.expiresAtMs > now) {','''            if (entry.expiresAtMs > now && generation == persistentSaveGeneration.get()) {''','cache generation guard')
s=once(s,'                playbackUrlCache[entry.mediaId] = AuthScopedCacheValue(','                playbackUrlCache.putIfAbsent(entry.mediaId, AuthScopedCacheValue(','avoid overwrite live URL')
s=once(s,'                    isYouTubeStream = entry.isYouTubeStream,\n                )','                    isYouTubeStream = entry.isYouTubeStream,\n                ))','close put')
s=once(s,'delay(30 * 60 * 1000L)\n                savePersistentUrlCache()','delay(60_000L)\n                savePersistentUrlCache()','persist URLs while alive')
# Bounded small per-song metadata caches; neither artwork nor media bytes are kept here.
s=s.replace('private val contentLengthCache = ConcurrentHashMap<String, Long>()','private val contentLengthCache = app.hush.music.polaris.PolarisSmallCache<Long>()')
s=s.replace('private val audioNormalizationFactorCache = ConcurrentHashMap<String, Float>()','private val audioNormalizationFactorCache = app.hush.music.polaris.PolarisSmallCache<Float>()')
s=s.replace('private val formatEntityCache = ConcurrentHashMap<String, FormatEntity>()','private val formatEntityCache = app.hush.music.polaris.PolarisSmallCache<FormatEntity>()')
# Low-rate snapshots give resource records playback context without a retained service callback.
s=once(s,'                delay(if (player.isPlaying) 10.seconds else 30.seconds)','''                delay(if (player.isPlaying) 10.seconds else 30.seconds)
                app.hush.music.polaris.PolarisResourceDiagnostics.player("service=active playing=${player.isPlaying} ready=${player.playWhenReady} state=${player.playbackState} queue=${player.mediaItemCount} index=${player.currentMediaItemIndex} id=${app.hush.music.innertube.PlaybackTrace.id(player.currentMediaItem?.mediaId)} position=${player.currentPosition} buffered=${player.bufferedPosition} resolves=${playbackUrlResolutionInFlight.size} prefetch=${playbackUrlPrefetchInFlight.size} history=${historyRecordingJobs.size} wake=${wakeLock?.isHeld}")''','playback sample')
s=once(s,'                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))','''                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                            app.hush.music.polaris.PolarisResourceDiagnostics.event("audio-underrun buffer=$bufferSize bufferMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs")
                        }
                        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                            app.hush.music.polaris.PolarisErrorReport.failure("audio-sink", audioSinkError)
                        }
                        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                            app.hush.music.polaris.PolarisErrorReport.failure("audio-codec", audioCodecError)
                        }
                        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                            app.hush.music.polaris.PolarisResourceDiagnostics.event("audio-session id=$audioSessionId")
                        }
                    })''','audio diagnostics')
p.write_text(s)
# A 30-minute device-global stop prevented a recovered parser from ever being tried.
p=b/'utils/StreamClientAvailability.kt';s=p.read_text();s=s.replace('30 * 60 * 1000L','30_000L');p.write_text(s)
p=b/'utils/YTPlayerUtils.kt';s=p.read_text();s=once(s,'        var localCandidateFailure: StreamCandidateException? = null','''        var localCandidateFailure: StreamCandidateException? = null
        var decipherFailedThisAttempt = false''','attempt-local decipher failure')
s=s.replace('if (isCipheredFormat(candidate) && !StreamClientAvailability.canDecipher())','if (isCipheredFormat(candidate) && (decipherFailedThisAttempt || !StreamClientAvailability.canDecipher()))')
s=once(s,'                            candidateEvent(candidate, "conversion-failed", error)','''                            candidateEvent(candidate, "conversion-failed", error)
                            if (error.isJavaScriptPlayerExtractorFailure()) decipherFailedThisAttempt = true''','one decipher failure per sweep')
s=once(s,'                .onSuccess { Timber.tag(logTag).i("Stream URL obtained successfully") }','''                .onSuccess { StreamClientAvailability.forgetAll(); Timber.tag(logTag).i("Stream URL obtained successfully") }''','clear stale failure')
p.write_text(s)
# Core extraction is deterministic and pinned: same 0.26.3 source with a focused signature backport.
p=r/'settings.gradle.kts';s=p.read_text();s+='''
includeBuild("../polaris-newpipe") {
    dependencySubstitution {
        substitute(module("com.github.TeamNewPipe:NewPipeExtractor")).using(project(":extractor"))
    }
}
''';p.write_text(s)
p=r/'app/build.gradle.kts';p.write_text(p.read_text().replace('versionCode = 100572','versionCode = 100573').replace('versionName = "50.10"','versionName = "50.11"'))
print('50.11 AA resource fixes and bounded forensic diagnostics applied')
