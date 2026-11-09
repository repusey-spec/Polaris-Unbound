#!/usr/bin/env python3
"""50.12: remove PCM churn and bound artwork, persistence and legacy logging work."""
from pathlib import Path
import shutil, sys
r = Path(sys.argv[1]); here = Path(__file__).resolve().parent
b = r / 'app/src/main/kotlin/app/hush/music'
def once(s, old, new, label):
    assert s.count(old) == 1, (label, s.count(old))
    return s.replace(old, new, 1)
for f in (here/'resources12').glob('*.kt'):
    if f.name.endswith('Test.kt'): dest = r/'app/src/test/kotlin/app/hush/music/polaris'/f.name
    elif f.name == 'CustomEqualizerAudioProcessor.kt': dest = b/'eq/audio'/f.name
    elif f.name == 'SpotiFLACDiag.kt': dest = b/'spotiflac'/f.name
    else: dest = b/'polaris'/f.name
    shutil.copyfile(f, dest)
p=b/'playback/MusicService.kt'; s=p.read_text()
s=once(s, '    private val persistentSaveGeneration = AtomicLong(0L)', '''    private val persistentSaveGeneration = AtomicLong(0L)
    private val queuePersistenceRevision = AtomicLong(0L)
    private val queueSaveRequests = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var queueSaveWorker: Job? = null
    private var lastQueueCheckpoint: app.hush.music.polaris.PolarisQueueCheckpointKey? = null
    private var notificationArtworkJob: Job? = null
    private var notificationArtworkKey: Pair<String, String?>? = null
    private val notificationArtworkGate = kotlinx.coroutines.sync.Semaphore(1)''', 'owned work fields')
assert 'import kotlinx.coroutines.sync.withPermit' in s
s=once(s, '    private suspend fun hydrateNotificationArtwork(mediaItem: MediaItem) {', '''    // Main-owned single flight: transition and READY must share one hydration.
    private fun scheduleNotificationArtwork(mediaItem: MediaItem) {
        val key = mediaItem.mediaId to NotificationArtworkLoader.resolveArtworkUrl(mediaItem)
        if (notificationArtworkKey == key && notificationArtworkJob?.isActive == true) return
        notificationArtworkJob?.cancel()
        notificationArtworkKey = key
        notificationArtworkJob = scope.launch(SilentHandler) { hydrateNotificationArtwork(mediaItem) }
    }

    private suspend fun hydrateNotificationArtwork(mediaItem: MediaItem) {''', 'artwork single flight')
s=once(s, '''            scope.launch(SilentHandler) {
                hydrateNotificationArtwork(item)
            }''', '            scheduleNotificationArtwork(item)', 'transition artwork')
s=once(s, '''                    scope.launch(SilentHandler) {
                        hydrateNotificationArtwork(item)
                    }''', '                    scheduleNotificationArtwork(item)', 'ready artwork')
a=s.index('        val bitmap =', s.index('    private suspend fun hydrateNotificationArtwork'))
z=s.index('\n        withContext(Dispatchers.Main.immediate) {', a)
s=s[:a]+'''        val artworkData = notificationArtworkGate.withPermit {
            withContext(Dispatchers.IO) {
                val started = android.os.SystemClock.elapsedRealtime()
                val bitmap = NotificationArtworkLoader.loadBitmap(url = artworkUrl, maxSizePx = 1080)
                    ?: return@withContext null
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    NotificationArtworkLoader.bitmapToArtworkData(bitmap)
                } finally {
                    bitmap.recycle()
                    app.hush.music.polaris.PolarisPlaybackWorkMetrics.artworkCompleted(android.os.SystemClock.elapsedRealtime() - started)
                }
            }
        } ?: return
''' +s[z:]
s=once(s, 'import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.ensureActive\n', 'cancellation import')
s=once(s, '''                    bitmap = bitmap,
                    artworkUrl = artworkUrl,''', '''                    artworkData = artworkData,
                    artworkUrl = artworkUrl,''', 'precompressed artwork')
# On source/timeline changes refresh the full queue checkpoint once. Position ticks remain small.
s=once(s, '        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {', '''        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            queuePersistenceRevision.incrementAndGet()''', 'queue revision')
a=s.index('    private suspend fun saveQueueToDisk() {'); z=s.index('\n    override fun onDestroy()', a)
s=s[:a]+'''    private suspend fun saveQueueToDisk() {
        // One running writer plus one latest pending request, never one writer per callback.
        withContext(Dispatchers.Main.immediate) {
            if (queueSaveWorker?.isActive != true) {
                queueSaveWorker = ioScope.launch(SilentHandler) {
                    for (generation in queueSaveRequests) {
                        delay(200L)
                        val latest = queueSaveRequests.tryReceive().getOrNull() ?: generation
                        if (latest == persistentSaveGeneration.get()) saveQueueCheckpoint()
                    }
                }
            }
            queueSaveRequests.trySend(persistentSaveGeneration.get())
        }
    }

    private suspend fun saveQueueCheckpoint() {
        val started = android.os.SystemClock.elapsedRealtime()
        val snapshot = withContext(Dispatchers.Main.immediate) {
            if (isRestoringPersistentState || isHydratingRestoredQueue || player.mediaItemCount == 0) return@withContext null
            val key = app.hush.music.polaris.PolarisQueueCheckpointKey(
                persistentSaveGeneration.get(), queuePersistenceRevision.get(), player.currentMediaItemIndex,
                player.currentMediaItem?.mediaId, queueTitle, currentQueue,
            )
            val queue = if (key.matches(lastQueueCheckpoint)) null else {
                val items = player.mediaItems.mapNotNull { it.toPersistableMetadata() }
                if (items.isEmpty()) return@withContext null
                currentQueue.toPersistQueue(title = queueTitle, items = items,
                    mediaItemIndex = player.currentMediaItemIndex, position = player.currentPosition)
            }
            val state = PersistPlayerState(
                playWhenReady = player.playWhenReady, repeatMode = player.repeatMode,
                shuffleModeEnabled = player.shuffleModeEnabled, volume = playerVolume.value,
                currentPosition = player.currentPosition, currentMediaItemIndex = player.currentMediaItemIndex,
                playbackState = player.playbackState, currentMediaId = player.currentMediaItem?.mediaId,
            )
            Triple(key, queue, state)
        } ?: return
        // Recheck inside the same disk lock as clear: an obsolete save cannot recreate cleared files.
        synchronized(persistentStateLock) {
            if (snapshot.first.generation != persistentSaveGeneration.get()) return
            if (snapshot.second != null && !writePersistentObject(PERSISTENT_QUEUE_FILE, snapshot.second!!)) return
            if (snapshot.first.generation != persistentSaveGeneration.get()) return
            if (!writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, snapshot.third)) return
            lastQueueCheckpoint = snapshot.first
        }
        app.hush.music.polaris.PolarisPlaybackWorkMetrics.checkpointCompleted(snapshot.second != null,
            android.os.SystemClock.elapsedRealtime() - started)
    }
''' +s[z:]
# Async requests must not claim the disk checkpoint has already completed.
s=s.replace('"queue persist ($reason): items=$itemCount position=$position saved=$saved"', '"queue persist requested ($reason): items=$itemCount position=$position accepted=$saved"')
# Preserve old Serializable identities; new nullable field is null in old files.
s=once(s, '''                    player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))''', '''                    val matchingTrack = playerState.currentMediaId == null || player.getMediaItemAt(index).mediaId == playerState.currentMediaId
                    if (matchingTrack) player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))''', 'checkpoint track identity')
# Make write success explicit. The key is committed only after both required writes succeeded.
a=s.index('    private fun writePersistentObject('); z=s.index('\n    private fun loadPersistentUrlCache()',a)
block=s[a:z]
block=once(block, '    ) {', '    ): Boolean {', 'write result type')
block=once(block, '        synchronized(persistentStateLock) {', '        return synchronized(persistentStateLock) {', 'write return')
block=once(block, '            }.onFailure {', '                true\n            }.getOrElse {', 'write failure')
block=once(block, '                reportException(it)', '                reportException(it)\n                false', 'write false')
s=s[:a]+block+s[z:]
s=once(s, '        super.onDestroy()\n        // Drop the resume hook', '''        notificationArtworkJob?.cancel()
        queueSaveWorker?.cancel()
        super.onDestroy()
        // Drop the resume hook''', 'cancel owned work')
s=once(s, '"audio-underrun buffer=$bufferSize bufferMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs"', '"audio-underrun buffer=$bufferSize bufferMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs " + app.hush.music.polaris.PolarisPlaybackWorkMetrics.summary()', 'audio work counters')
# Avoid diagnostic disk traffic when the published source label did not change.
s=once(s, '''        // Logged for every publish, not just the download branch's:''', '''        if (lastPublishedPlaybackClient == published) return
        // Log only source-label changes; repeated cache reads are not new events.
        // Logged for every publish, not just the download branch's:''', 'label dedupe')
p.write_text(s)
p=b/'models/PersistPlayerState.kt'; s=p.read_text(); s=once(s, '    val timestamp: Long = System.currentTimeMillis(),', '    val timestamp: Long = System.currentTimeMillis(),\n    val currentMediaId: String? = null,', 'compatible checkpoint id');p.write_text(s)
p=b/'utils/NotificationArtworkLoader.kt'; s=p.read_text();s=once(s,'        bitmap: Bitmap,\n        artworkUrl: String?,','        artworkData: ByteArray,\n        artworkUrl: String?,','artwork helper data');s=once(s,'            bitmapToArtworkData(bitmap),','            artworkData,','no main compression');p.write_text(s)
p=b/'polaris/PolarisDiagnosticJournal.kt';s=p.read_text().replace('"playback", "stream", "radio", "resource"','"playback", "stream", "radio", "resource", "spotiflac"');s=once(s, '            if (target.length() + value.toByteArray(Charsets.UTF_8).size > FILE_LIMIT) {', '            val bytes = value.toByteArray(Charsets.UTF_8)\n            if (target.length() + bytes.size > FILE_LIMIT) {','one encoding');s=s.replace('            val bytes = value.toByteArray(Charsets.UTF_8)\n            target.appendBytes','            target.appendBytes');p.write_text(s)
p=b/'polaris/PolarisErrorReport.kt';s=p.read_text().replace('DIAGNOSTICS (50.11)', 'DIAGNOSTICS (50.12)');s=once(s, '            appendLine("=== PROCESS / DEVICE RESOURCE DIAGNOSTICS ===")', '            appendLine("=== BOUNDED SOURCE ROUTING DIAGNOSTICS ===")\n            appendLine(PolarisDiagnosticJournal.dump("spotiflac"))\n            appendLine("=== PROCESS / DEVICE RESOURCE DIAGNOSTICS ===")', 'source report');p.write_text(s)
p=b/'polaris/PolarisResourceDiagnostics.kt';s=p.read_text();s=once(s,'            event("sample AA=$aa $playback $metrics")','            event("sample AA=$aa $playback $metrics " + PolarisPlaybackWorkMetrics.summary())','sample work counters');p.write_text(s)
p=r/'app/build.gradle.kts';p.write_text(p.read_text().replace('versionCode = 100573','versionCode = 100574').replace('versionName = "50.11"','versionName = "50.12"'))
print('50.12 bounded playback work and checkpoint changes applied')
