#!/usr/bin/env python3
"""50.8: bounded validation/resolution, failed-task management, preference-aware recovery."""
from pathlib import Path
import shutil, sys
r = Path(sys.argv[1]); b = r/'app/src/main/kotlin/app/hush/music'; here = Path(__file__).resolve().parent
def once(s, old, new, label):
    assert s.count(old) == 1, (label, s.count(old))
    return s.replace(old, new, 1)
for src in (here/'downloads08').glob('*.kt'):
    target = (r/'app/src/test/kotlin/app/hush/music/polaris' if src.name.endswith('Test.kt') else b/'polaris')/src.name
    target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(src, target)

p=b/'playback/DownloadUtil.kt'; s=p.read_text()
s=once(s, '        val serialDownloadPending get() = serialQueue.pending', '''        val serialDownloadPending get() = serialQueue.pending
        val serialDownloadTasks get() = serialQueue.entries
        private fun recordDownloadProgress(id: String, stage: String, bytes: Long = 0L, expected: Long = -1L, tempPath: String = "") {
            val previous = serialQueue.snapshot().firstOrNull { it.id == id }
            serialQueue.progress(id, stage, bytes, expected, tempPath)
            if (previous?.stage != stage) app.hush.music.polaris.PolarisErrorReport.record(
                "download-phase id=$id stage=$stage prior=${previous?.stage} priorElapsedMs=${System.currentTimeMillis() - (previous?.updatedAt ?: System.currentTimeMillis())} bytes=$bytes expected=$expected")
        }
        private fun cleanFailedPart(task: app.hush.music.polaris.PolarisSerialDownloads.Task) {
            if (task.tempPath.isBlank()) return
            val part = File(task.tempPath)
            val directory = StorageLocationRepository.cacheDirectory(appContext, StorageFolderKind.DOWNLOADS)
            if (part.name.endsWith(".part") && part.parentFile?.canonicalFile == directory.canonicalFile) {
                if (part.exists() && !part.delete()) throw IOException("cannot remove unfinished download")
            }
        }
        fun retryFailedDownload(id: String) {
            try {
                if (serialQueue.retryFailed(id, ::cleanFailedPart)) app.hush.music.polaris.PolarisDownloadService.start(appContext)
            } catch (error: Exception) { app.hush.music.polaris.PolarisErrorReport.failure("download-retry-request id=$id", error) }
        }
        fun retryAllFailedDownloads() { serialQueue.snapshot().filter { it.state == app.hush.music.polaris.PolarisSerialDownloads.State.FAILED }.forEach { retryFailedDownload(it.id) } }
        fun discardFailedDownload(id: String) {
            try {
                if (!serialQueue.discardFailed(id, ::cleanFailedPart)) return
                fileDownloads.update { rows -> if (rows[id]?.state == Download.STATE_FAILED) rows - id else rows }
                app.hush.music.polaris.PolarisErrorReport.record("download-failure-cleared id=$id savedFilePreserved=true")
            } catch (error: Exception) { app.hush.music.polaris.PolarisErrorReport.failure("download-failure-clear id=$id", error) }
        }''', 'safe failed task API')
s=once(s, '        private val dataSourceFactory =', '''        private val boundedFileDownloadFactory = DefaultDataSource.Factory(appContext,
            ChunkingDataSourceFactory(OkHttpDataSource.Factory(mediaOkHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).build())))

        private val dataSourceFactory =''', 'bounded file reads without changing playback factory')
s=once(s, '            val startedAt = System.currentTimeMillis()', '            val startedAt = System.currentTimeMillis()\n            val resolveDeadline = SystemClock.elapsedRealtime() + app.hush.music.polaris.PolarisDownloadPolicy.RESOLVE_MS', 'one resolution budget shared by retries')
s=once(s, 'transfer = { downloadToFile(mediaId, title, startedAt) })', 'transfer = { downloadToFile(mediaId, title, startedAt, resolveDeadline) })', 'shared resolution deadline')
start=s.index('        private suspend fun downloadToFile('); end=s.index('        private fun claimTargetFile(',start)
block=s[start:end]
block=once(block, '            startedAt: Long,', '            startedAt: Long,\n            resolveDeadline: Long,', 'download budget parameter')
block=once(block, '                    lastPublished.set(now)', '''                    lastPublished.set(now)
                    recordDownloadProgress(mediaId, "수신", written, total)''', 'live transfer phase')
block=once(block, '                resolveSpotiFLACDownloadUri(mediaId)?.takeIf', '''                recordDownloadProgress(mediaId, "주소 조회")
                app.hush.music.polaris.PolarisDownloadPolicy.resolve(resolveDeadline, { SystemClock.elapsedRealtime() }) {
                    kotlinx.coroutines.runInterruptible { resolveSpotiFLACDownloadUri(mediaId) }
                }?.takeIf''', 'interruptible provider resolution within common deadline')
block=once(block, 'copyToFile(source, target, keepGoing, onProgress)', 'copyToFile(mediaId, source, target, keepGoing, onProgress)', 'trace local copy identity')
block=once(block, '                    runBlocking(Dispatchers.IO) {', '                    app.hush.music.polaris.PolarisDownloadPolicy.resolve(resolveDeadline, { SystemClock.elapsedRealtime() }) {', 'structured YouTube resolver cancellation')
s=s[:start]+block+s[end:]
s=s.replace('        private fun copyToFile(\n            source: File,', '        private suspend fun copyToFile(\n            mediaId: String, source: File,')
s=s.replace('        private fun fetchToFile(\n', '        private suspend fun fetchToFile(\n')
start=s.index('        private suspend fun copyToFile('); end=s.index('        /**\n         * Publishes progress', start)
block=s[start:end]
block=block.replace('val dataSource = downloadUpstreamFactory.createDataSource()', 'val dataSource = boundedFileDownloadFactory.createDataSource()')
block=block.replace('                app.hush.music.polaris.PolarisDownloadValidation.validate(part, keepGoing)', '''                recordDownloadProgress(mediaId, "검증", part.length(), part.length(), part.absolutePath)
                app.hush.music.polaris.PolarisDownloadValidation.validate(appContext, part, true, keepGoing)''',1)
block=block.replace('                app.hush.music.polaris.PolarisDownloadValidation.validate(part, keepGoing)', '''                recordDownloadProgress(mediaId, "검증", written, expected, part.absolutePath)
                app.hush.music.polaris.PolarisDownloadValidation.validate(appContext, part, expected > 0, keepGoing)''',1)
block=block.replace('} catch (error: Exception) { part.delete(); throw error }', '} catch (error: Exception) { if (error !is app.hush.music.polaris.PolarisVerificationTimeout) part.delete(); throw error }')
block=block.replace('                part.delete()\n                throw error', '                if (error !is app.hush.music.polaris.PolarisVerificationTimeout) part.delete()\n                throw error')
s=s[:start]+block+s[end:]
p.write_text(s)

# Probe process only parses private files; do not initialize accounts, players or update jobs there.
p=b/'App.kt';s=p.read_text()
s=once(s, '        instance = this', '        instance = this\n        if (currentProcessName()?.endsWith(":polaris_verify") == true) return', 'minimal probe application process')
p.write_text(s)

# The original preference did not apply to confirmation/verification holds.
p=b/'playback/MusicService.kt'; s=p.read_text()
s=once(s, '        val noPlayableSource = error.findNoPlayableSourceException()', '''        val noPlayableSource = error.findNoPlayableSourceException()
        if (noPlayableSource != null && dataStore.get(AutoSkipNextOnErrorKey, false) && player.playWhenReady) {
            polarisFinalPlaybackFailure(error, "no-source")
            return
        }''', 'auto skip applies before optional verification hold')
start=s.index('        val confirmationRequired = error.playbackConfirmationRequiredCause()'); end=s.index('        // Failing on bytes',start)
block=s[start:end]; block=once(block, '            stopOnError()', '            polarisFinalPlaybackFailure(error, "authentication")', 'authentication honors preference')
s=s[:start]+block+s[end:]
start=s.index('    override fun onPlayerError(error: PlaybackException)'); end=s.index('    /** Removes only transient',start)
block=s[start:end]
old='''        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }'''
block=once(block,old,'        polarisFinalPlaybackFailure(error, "retry-exhausted")','final failure decision');s=s[:start]+block+s[end:]
s=once(s, '    private fun skipOnError() {', '''    private fun polarisFinalPlaybackFailure(error: PlaybackException, reason: String) {
        val auto = dataStore.get(AutoSkipNextOnErrorKey, false)
        val decision = app.hush.music.polaris.PolarisPlaybackFailurePolicy.decide(auto,
            isNetworkConnected.value, player.playWhenReady, consecutivePlaybackErr,
            player.currentMediaItemIndex, player.nextMediaItemIndex)
        app.hush.music.polaris.PolarisErrorReport.record("playback-decision=$decision reason=$reason autoSkip=$auto wanted=${player.playWhenReady} id=${player.currentMediaItem?.mediaId} next=${player.nextMediaItemIndex} consecutive=$consecutivePlaybackErr retry=${playbackStreamRecoveryTracker.retryCountFor(player.currentMediaItem?.mediaId.orEmpty())} code=${error.errorCode}")
        when (decision) {
            app.hush.music.polaris.PolarisPlaybackFailurePolicy.Decision.SKIP -> skipOnError()
            app.hush.music.polaris.PolarisPlaybackFailurePolicy.Decision.WAIT -> waitOnNetworkError()
            else -> stopOnError()
        }
    }

    private fun skipOnError() {''', 'single explicit final decision')
s=once(s, 'consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET', 'consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET && nextWindowIndex != player.currentMediaItemIndex', 'prevent one-item repeat loop')
s=once(s,'            player.seekTo(nextWindowIndex, C.TIME_UNSET)', '''            app.hush.music.polaris.PolarisErrorReport.record("playback-skip from=${player.currentMediaItem?.mediaId} to=${player.getMediaItemAt(nextWindowIndex).mediaId} consecutive=$consecutivePlaybackErr")
            player.seekTo(nextWindowIndex, C.TIME_UNSET)''','record actual skip')
# Share a 30 second recovery budget across URL refresh attempts for a playing item.
s=once(s, '    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()',
    '    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()\n    private val polarisPlaybackRecoveryDeadlines = java.util.concurrent.ConcurrentHashMap<String, Long>()', 'playback recovery clock')
s=once(s, '                    resolveAndCachePlaybackUrlInternal(mediaId, preferredClientOverride)', '\n'.join([
    '                    val deadline = polarisPlaybackRecoveryDeadlines[mediaId] ?: (android.os.SystemClock.elapsedRealtime() + app.hush.music.polaris.PolarisDownloadPolicy.RESOLVE_MS)',
    '                    app.hush.music.polaris.PolarisDownloadPolicy.resolve(deadline, { android.os.SystemClock.elapsedRealtime() }) {',
    '                        resolveAndCachePlaybackUrlInternal(mediaId, preferredClientOverride)',
    '                    }']), 'bound shared playback resolution not just the waiting caller')
anchor='        // A "no enabled source can serve this track" failure arrives here as a generic source'
s=once(s, anchor, '\n'.join([
    '        val recoveryDeadline = polarisPlaybackRecoveryDeadlines.getOrPut(currentMediaId) { android.os.SystemClock.elapsedRealtime() + app.hush.music.polaris.PolarisDownloadPolicy.RESOLVE_MS }',
    '        if (android.os.SystemClock.elapsedRealtime() >= recoveryDeadline || generateSequence<Throwable>(error) { it.cause }.take(12).any { it is app.hush.music.polaris.PolarisResolutionTimeout }) {',
    '            polarisFinalPlaybackFailure(error, "resolution-timeout")',
    '            return',
    '        }',
    ''])+anchor, 'exhausted time budget is a final failure')
# READY is not evidence that a damaged sample was actually playable. Reset only after progress.
a=s.index('            playbackStreamRecoveryTracker.onPlaybackRecovered(currentMediaId)')
z=s.index('            if (player.isPlaying && urlRefreshJob',a)
s=s[:a]+'            polarisScheduleRecoveryReset(currentMediaId)\n'+s[z:]
s=once(s, '    private fun polarisFinalPlaybackFailure(', '\n'.join([
    '    private var polarisRecoveryResetJob: Job? = null',
    '    private fun polarisScheduleRecoveryReset(id: String?) {',
    '        if (id == null || !player.isPlaying) return',
    '        polarisRecoveryResetJob?.cancel()',
    '        val position = player.currentPosition',
    '        polarisRecoveryResetJob = scope.launch {',
    '            delay(1_500L)',
    '            if (app.hush.music.polaris.PolarisPlaybackFailurePolicy.recovered(id, player.currentMediaItem?.mediaId, player.playWhenReady, player.isPlaying, position, player.currentPosition)) {',
    '                playbackStreamRecoveryTracker.onPlaybackRecovered(id)',
    '                polarisPlaybackRecoveryDeadlines.remove(id)',
    '                consecutivePlaybackErr = 0',
    '            }',
    '        }',
    '    }',
    '',
    '    private fun polarisFinalPlaybackFailure(']), 'reset retry counters only after genuine playback progress')
s=once(s, '        super.onMediaItemTransition(mediaItem, reason)',
    '        super.onMediaItemTransition(mediaItem, reason)\n        mediaItem?.mediaId?.let { polarisPlaybackRecoveryDeadlines.remove(it) }', 'manual selection gets a fresh recovery budget')
p.write_text(s)

# Completed rows must not count as an active download forever.
p=b/'ui/utils/HeaderDownloadState.kt'; s=p.read_text()
s=once(s, '                progressTotal += 1f\n                hasRunningDownload = true', '                progressTotal += 1f', 'completed is not running')
p.write_text(s)
p=b/'ui/screens/playlist/LocalPlaylistScreen.kt'; s=p.read_text()
s=once(s, '                songs.isNotEmpty() &&\n                !downloadProgressToolbarDismissed', '                songs.isNotEmpty() &&\n                hasActiveDownloads(songs.map { it.song.id }, downloads) &&\n                !downloadProgressToolbarDismissed', 'no active work means no running toolbar')
p.write_text(s)

# Easy entry points both beside the error report and inside download management.
p=b/'ui/screens/settings/SettingsScreen.kt'; s=p.read_text()
anchor='''                }) { Text("Polaris 오류 리포트 보기·공유") }'''
s=once(s,anchor,anchor+'''
                androidx.compose.material3.TextButton(onClick = {
                    app.hush.music.polaris.PolarisDownloadsActivity.open(context)
                }) { Text("다운로드 관리 · 실패 확인/재시도") }''','download manager entry')
p.write_text(s)
p=b/'ui/screens/playlist/DownloadManagementScreen.kt';s=p.read_text()
anchor='''            LazyColumn('''
idx=s.find('                downloadSection(')
assert idx>0
s=s[:idx]+'''                item {
                    androidx.compose.material3.TextButton(onClick = {
                        app.hush.music.polaris.PolarisDownloadsActivity.open(context)
                    }) { Text("다운로드 관리 · 실패 확인/재시도") }
                }
'''+s[idx:];# The original failed-row buttons use the same safe API as the new management screen.
s=once(s, '    val playerConnection = LocalPlayerConnection.current',
    '    val playerConnection = LocalPlayerConnection.current\n    val polarisDownloads = LocalDownloadUtil.current', 'capture download util for failed actions')
a=s.index('                    title = failedTitle,');z=s.index('                    title = completedTitle,',a)
f=s[a:z]
start=f.index('                        onPrimaryAction = {');end=f.index('                        onRemove = ',start)
f=f[:start]+'                        onPrimaryAction = { polarisDownloads.retryFailedDownload(download.request.id) },\n'+f[end:]
f=once(f,'onRemove = { sendRemoveDownloads(context, listOf(download.request.id)) }','onRemove = { polarisDownloads.discardFailedDownload(download.request.id) }','failed row clear preserves completed files')
s=s[:a]+f+s[z:];p.write_text(s)

# Distributions and all automatic/manual app updater paths use this same false flag.
p=r/'app/build.gradle.kts';s=p.read_text()
assert s.count('buildConfigField("boolean", "UPDATER_AVAILABLE", "true")')>=1
s=s.replace('buildConfigField("boolean", "UPDATER_AVAILABLE", "true")','buildConfigField("boolean", "UPDATER_AVAILABLE", "false")')
s=s.replace('versionCode = 100569','versionCode = 100570').replace('versionName = "50.7"','versionName = "50.8"');p.write_text(s)
p=b/'utils/Updater.kt';s=p.read_text()
s=once(s,'        val latestSemVer = parseSemVerOrNull(latestVersion)','        if (!BuildConfig.UPDATER_AVAILABLE) return false\n        val latestSemVer = parseSemVerOrNull(latestVersion)','cached updater state cannot advertise Hush')
p.write_text(s)
p=b/'ui/screens/settings/UpdateScreen.kt';s=p.read_text()
idx=s.index('    val context = LocalContext.current')
s=s[:idx]+'''    if (!BuildConfig.UPDATER_AVAILABLE) {
        androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
            Text("Polaris 업데이트", style = MaterialTheme.typography.titleLarge)
            Text("Polaris는 별도로 제공되는 APK로 업데이트합니다.")
            androidx.compose.material3.TextButton(onClick = { navController.navigateUp() }) { Text("뒤로") }
        }
        return
    }
'''+s[idx:];p.write_text(s)
p=r/'app/src/main/AndroidManifest.xml';s=p.read_text()
s=once(s,'        <activity android:name="app.hush.music.polaris.PolarisReportActivity"', '''        <activity android:name="app.hush.music.polaris.PolarisDownloadsActivity" android:exported="false" />
        <service android:name="app.hush.music.polaris.PolarisProbeService" android:exported="false" android:process=":polaris_verify" />
        <activity android:name="app.hush.music.polaris.PolarisReportActivity"''','bounded probe process and management activity')
p.write_text(s)
print('PASS 50.8 performance, failed queue management, skip preference and updater suppression')
