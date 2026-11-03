#!/usr/bin/env python3
"""50.7 over the verified 50.6 source: one durable file queue and checked commits."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
base = root / 'app/src/main/kotlin/app/hush/music'
here = Path(__file__).resolve().parent

def once(s, old, new, label):
    assert s.count(old) == 1, f'{label}: {s.count(old)} anchors'
    return s.replace(old, new, 1)

for src in (here / 'downloads07').glob('*.kt'):
    if src.name == 'ChunkingDataSource.kt': target = base / 'playback' / src.name
    elif src.name.endswith('Test.kt'): target = root / 'app/src/test/kotlin/app/hush/music/polaris' / src.name
    else: target = base / 'polaris' / src.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, target)

path = base / 'playback/DownloadUtil.kt'
s = path.read_text()
s = once(s, '        private val appContext = context', '''        private val appContext = context.also { app.hush.music.polaris.PolarisErrorReport.install(it) }''', 'download reports initialized without music service')
s = once(s, '        private val fileDownloadJobs = ConcurrentHashMap<String, Job>()', '''        private val serialStore = app.hush.music.polaris.PolarisDownloadStore(appContext)
        private val serialQueue: app.hush.music.polaris.PolarisSerialDownloads by lazy {
            app.hush.music.polaris.PolarisSerialDownloads(
                scope = downloadScope,
                restored = serialStore.read(),
                persist = serialStore::write,
                changed = { task ->
                    app.hush.music.polaris.PolarisErrorReport.record("download-queue id=${task.id} attempt=${task.token} state=${task.state} active=${serialQueueActiveId()}")
                    val state = when (task.state) {
                        app.hush.music.polaris.PolarisSerialDownloads.State.ACTIVE -> Download.STATE_DOWNLOADING
                        app.hush.music.polaris.PolarisSerialDownloads.State.PAUSED -> Download.STATE_STOPPED
                        app.hush.music.polaris.PolarisSerialDownloads.State.FAILED -> Download.STATE_FAILED
                        else -> Download.STATE_QUEUED
                    }
                    if (task.state != app.hush.music.polaris.PolarisSerialDownloads.State.COMPLETE) {
                        publishFileDownload(task.id, task.title, state, 0L, 0L, System.currentTimeMillis(),
                            stopReason = if (state == Download.STATE_STOPPED) PAUSED_STOP_REASON else Download.STOP_REASON_NONE,
                            failureReason = if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE)
                    }
                },
                failed = { task, error ->
                    app.hush.music.polaris.PolarisErrorReport.failure("download-final id=${task.id} attempt=${task.token}", error)
                    if (connectivityManager.activeNetwork == null || error.message?.contains("ENOSPC", true) == true || error.message?.contains("No space left", true) == true) {
                        pauseSerialDownloads()
                    }
                },
                fatal = { error -> app.hush.music.polaris.PolarisErrorReport.failure("download-queue-storage", error) },
                execute = { task -> runFileDownload(task.id, task.title) },
            )
        }
        private fun serialQueueActiveId(): String = serialQueue.activeId() ?: "none"
        val serialDownloadPending get() = serialQueue.pending
        fun startSerialDownloads() { serialQueue.start() }
        fun stopSerialDownloads() { serialQueue.stop() }
        fun pauseSerialDownloads() { serialQueue.pauseAll() }
        fun resumeSerialDownloads() {
            serialQueue.resumeAll()
            if (serialQueue.pending.value) app.hush.music.polaris.PolarisDownloadService.start(appContext)
        }''', 'single persistent file queue')
s = once(s, '                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS', '''                maxParallelDownloads = 1
                // Old cache tasks are parked; explicit resume enters the same file queue.
                pauseDownloads()''', 'park legacy independent downloader')
start = s.index('            // Pre-resolve pending download URLs immediately')
end = s.index('\n        private val downloadFailureCounts', start)
s = s[:start] + '''            // No parallel address pre-resolution for queued downloads. The single worker
            // resolves only the file it currently owns.
        }
''' + s[end:]
# Restore pending/paused/failed UI rows without overwriting a freshly enqueued task.
s = once(s, '                fileDownloads.value = restored', '''                fileDownloads.update { restored + it }
                serialQueue.snapshot().forEach { task ->
                    val state = when (task.state) {
                        app.hush.music.polaris.PolarisSerialDownloads.State.PAUSED -> Download.STATE_STOPPED
                        app.hush.music.polaris.PolarisSerialDownloads.State.FAILED -> Download.STATE_FAILED
                        else -> Download.STATE_QUEUED
                    }
                    fileDownloads.update { current -> if (current.containsKey(task.id)) current else current +
                        (task.id to syntheticDownload(task.id, task.title, state, 0L, 0L, System.currentTimeMillis(),
                            if (state == Download.STATE_STOPPED) PAUSED_STOP_REASON else Download.STOP_REASON_NONE,
                            if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE)) }
                }''', 'restore durable queue UI')
start = s.index('            val job =\n', s.index('        fun requestFileDownload(\n            mediaId: String'))
end = s.index('\n        /**\n         * Removes a download', start)
s = s[:start] + '''            try {
                serialQueue.enqueue(id, title)
                app.hush.music.polaris.PolarisDownloadService.start(appContext)
            } catch (error: Exception) {
                app.hush.music.polaris.PolarisErrorReport.failure("download-queue-request id=$id", error)
            }
        }

        fun pauseDownload(mediaId: String) {
            val id = mediaId.trim()
            serialQueue.pause(id)
            runCatching { downloadManager.setStopReason(id, PAUSED_STOP_REASON) }
        }

        fun resumeDownload(mediaId: String) {
            val id = mediaId.trim()
            if (id.isBlank()) return
            val title = serialQueue.snapshot().firstOrNull { it.id == id }?.title
                ?: downloads.value[id]?.let { String(it.request.data, Charsets.UTF_8) }.orEmpty()
            // A legacy resume also becomes a real-file queue item. The old cache remains
            // available until a verified new copy completes; no parallel manager transfer.
            requestFileDownload(id, title)
        }
''' + s[end:]
s = once(s, '            fileDownloadJobs.remove(id)?.cancel()', '            serialQueue.remove(id)', 'remove cancels single worker child')
s = once(s, '            fileDownloadJobs.keys.toList().forEach { id -> fileDownloadJobs.remove(id)?.cancel() }', '            serialQueue.clear()', 'clear durable queue')
s = once(s, 'fileDownloads.value.containsKey(mediaId) || downloadedFileStore.get(mediaId) != null', 'serialQueue.contains(mediaId) || fileDownloads.value.containsKey(mediaId) || downloadedFileStore.get(mediaId) != null', 'pending membership')
s = once(s, '                val result = downloadToFile(mediaId, title, startedAt)', '''                val result = app.hush.music.polaris.PolarisDownloadRetry.run(mediaId,
                    report = { attempt, failure -> app.hush.music.polaris.PolarisErrorReport.failure("download-transfer id=$mediaId retry=$attempt", failure) },
                    invalidate = {
                        synchronized(songUrlCache) { songUrlCache.keys.filter { it.startsWith("$mediaId:") }.forEach { songUrlCache.remove(it) } }
                        YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
                    },
                    transfer = { downloadToFile(mediaId, title, startedAt) })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()''', 'bounded retry includes original cause')
# Imports extension ensureActive for the commit boundary.
s = once(s, 'import kotlinx.coroutines.launch', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.ensureActive', 'cancellation at commit boundary')
s = once(s, '                downloadedFileStore.record(entry)', '''                downloadedFileStore.record(entry)
                app.hush.music.polaris.PolarisErrorReport.record("download-index-committed id=$mediaId bytes=${entry.bytes}")''', 'index commit evidence')
s = once(s, '            } catch (error: Exception) {\n                android.util.Log.w(TAG, "file download failed:', '            } catch (error: Exception) {\n                app.hush.music.polaris.PolarisErrorReport.failure("download-file id=$mediaId", error)\n                android.util.Log.w(TAG, "file download failed:', 'file failure persisted')
old = '''                    failureReason = Download.FAILURE_REASON_UNKNOWN,
                )
            }
        }

        private class ResolvedDownloadFile'''
s = once(s, old, old.replace('                )\n            }', '                )\n                throw error\n            }'), 'worker sees failed file and proceeds')
# A stale cache record can never resume independent background work.
s = s.replace('runCatching { downloadManager.addDownload(download.request) }', 'runCatching { requestFileDownload(download.request) }')
s = once(s, '                    fetchToFile(playbackData.streamUrl, target,', '                    fetchToFile(mediaId, playbackData.streamUrl, target,', 'key each byte transfer')
start = s.index('        private fun commitPartFile(')
end = s.index('\n        /**\n         * Publishes progress', start)
s = s[:start] + '''        private fun copyToFile(
            source: File, target: File, keepGoing: () -> Boolean, onProgress: (Long, Long) -> Unit,
        ) {
            val part = partFileFor(target)
            try {
                source.inputStream().use { input -> part.outputStream().use { output ->
                    app.hush.music.polaris.PolarisDownloadIntegrity.copy({ input.read(it) }, output, source.length(), keepGoing, onProgress)
                    output.flush(); output.fd.sync()
                } }
                app.hush.music.polaris.PolarisDownloadValidation.validate(part, keepGoing)
                app.hush.music.polaris.PolarisDownloadValidation.commit(part, target, keepGoing)
            } catch (error: Exception) { part.delete(); throw error }
        }

        private fun fetchToFile(
            mediaId: String, url: String, target: File, declaredLength: Long,
            keepGoing: () -> Boolean, onProgress: (Long, Long) -> Unit,
        ) {
            val part = partFileFor(target)
            val dataSource = downloadUpstreamFactory.createDataSource()
            try {
                val opened = dataSource.open(DataSpec.Builder().setUri(url.toUri()).setKey(mediaId).build())
                // ChunkingDataSource reports a total only from the original request or a
                // validated Content-Range; a five-megabyte HTTP part is never a song size.
                if (declaredLength > 0 && opened > 0 && declaredLength != opened) {
                    throw java.io.IOException("download source length changed declared=$declaredLength opened=$opened")
                }
                val expected = if (declaredLength > 0) declaredLength else opened
                app.hush.music.polaris.PolarisErrorReport.record("download-open id=$mediaId expected=$expected lengthKnown=${expected > 0}")
                val written = part.outputStream().use { output ->
                    val count = app.hush.music.polaris.PolarisDownloadIntegrity.copy(
                        { dataSource.read(it, 0, it.size) }, output, expected, keepGoing, onProgress)
                    output.flush(); output.fd.sync()
                    count
                }
                app.hush.music.polaris.PolarisErrorReport.record("download-size id=$mediaId expected=$expected actual=$written verifying=true")
                app.hush.music.polaris.PolarisDownloadValidation.validate(part, keepGoing)
                app.hush.music.polaris.PolarisDownloadValidation.commit(part, target, keepGoing)
                app.hush.music.polaris.PolarisErrorReport.record("download-file-committed id=$mediaId bytes=$written")
            } catch (error: Exception) {
                app.hush.music.polaris.PolarisErrorReport.failure("download-validation id=$mediaId expected=$declaredLength actual=${part.length()}", error)
                part.delete()
                throw error
            } finally { runCatching { dataSource.close() } }
        }
''' + s[end:]
s = s.replace('private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6', 'private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 1')
assert 'fileDownloadJobs' not in s
path.write_text(s)

path = base / 'playback/ExoDownloadService.kt'
s = path.read_text()
s = once(s, '        when (intent?.action) {', '''        when (intent?.action) {
            DownloadService.ACTION_PAUSE_DOWNLOADS -> downloadUtil.pauseSerialDownloads()
            DownloadService.ACTION_RESUME_DOWNLOADS -> {
                downloadUtil.resumeSerialDownloads()
                return super.onStartCommand(Intent(this, ExoDownloadService::class.java), flags, startId)
            }''', 'global resume cannot unpark independent legacy manager')
# Swallow the manager's stop-reason resume command after forwarding to the unified queue.
s = once(s, '''                }
            }

            REMOVE_ALL_PENDING_DOWNLOADS''', '''                }
                return super.onStartCommand(Intent(this, ExoDownloadService::class.java), flags, startId)
            }

            REMOVE_ALL_PENDING_DOWNLOADS''', 'legacy stop reason never starts a second transfer')
path.write_text(s)

# Atomic download-index writes must not silently report completion after a failed save.
path = base / 'downloads/DownloadedFileStore.kt'
s = path.read_text()
start = s.index('        private fun persist() {')
s = s[:start] + '''        @Synchronized private fun persist() {
            val snapshot = DownloadedFileIndex(entries = entries.values.toList())
            val atomic = android.util.AtomicFile(indexFile)
            val stream = atomic.startWrite()
            try {
                stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                app.hush.music.polaris.PolarisErrorReport.failure("download-index-save", error)
                throw error
            }
        }
    }
'''
s = once(s, 'json.decodeFromString<DownloadedFileIndex>(indexFile.readText())', 'json.decodeFromString<DownloadedFileIndex>(String(android.util.AtomicFile(indexFile).readFully(), Charsets.UTF_8))', 'index recovery reads atomic backup')
path.write_text(s)

path = base / 'playback/MusicService.kt'
s = path.read_text()
s = once(s, '            Timber.tag(TAG).i("serving downloaded file for mediaId=$mediaId: ${downloaded.name}")', '''            app.hush.music.polaris.PolarisErrorReport.record("playback-local id=$mediaId bytes=${downloaded.length()} recorded=${downloadedFileStore.get(mediaId)?.bytes} route=download-file")
            Timber.tag(TAG).i("serving downloaded file for mediaId=$mediaId: ${downloaded.name}")''', 'actual local route related to next track')
path.write_text(s)

path = root / 'app/src/main/AndroidManifest.xml'
s = path.read_text()
s = once(s, '        <activity android:name="app.hush.music.polaris.PolarisReportActivity"', '''        <service android:name="app.hush.music.polaris.PolarisDownloadService" android:exported="false" android:foregroundServiceType="dataSync" />
        <activity android:name="app.hush.music.polaris.PolarisReportActivity"''', 'file downloads foreground lifecycle')
path.write_text(s)
path = root / 'app/build.gradle.kts'
s = path.read_text().replace('versionCode = 100568', 'versionCode = 100569').replace('versionName = "50.6"', 'versionName = "50.7"')
path.write_text(s)
print('PASS 50.7: durable FIFO, checked file commit, original read errors and download reports')
