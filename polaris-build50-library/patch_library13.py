#!/usr/bin/env python3
"""50.13: disabled updater safety and background local playback evidence."""
from pathlib import Path
import shutil, sys
r = Path(sys.argv[1]); here = Path(__file__).resolve().parent
b = r / 'app/src/main/kotlin/app/hush/music'
def once(s, old, new):
    assert s.count(old) == 1, (old[:100], s.count(old))
    return s.replace(old, new, 1)
p = b/'utils/UpdateNotificationManager.kt'; s = p.read_text()
s = once(s, '''    fun schedulePeriodicUpdateCheck(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            cancelPeriodicUpdateCheck(context)
            return
        }''', '''    fun schedulePeriodicUpdateCheck(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) return''')
s = once(s, '''    fun cancelPeriodicUpdateCheck(context: Context) {
        WorkManager.getInstance(context)''', '''    fun cancelPeriodicUpdateCheck(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) return
        WorkManager.getInstance(context)''')
s = once(s, '''    fun checkForUpdates(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            cancelPeriodicUpdateCheck(context)
            cancelUpdateNotification(context)''', '''    fun checkForUpdates(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            cancelUpdateNotification(context)''')
p.write_text(s)
p = b/'playback/MusicService.kt'; s = p.read_text()
s = once(s, '    private var lastPublishedPlaybackClient: Pair<String, String>? = null', '''    private var lastPublishedPlaybackClient: Pair<String, String>? = null
    // Main-owned: discard a slow source-label lookup superseded by a newer publish.
    private var playbackLabelRevision = 0L''')
a = s.index('        val published = mediaId to labelWithDeviceFileAttribute', s.index('    private fun publishPlaybackClientLabel('))
z = s.index('\n    private suspend fun resolveAndCachePlaybackUrl(', a)
s = s[:a] + '''        scope.launch {
            fun isCurrentTrack() = currentMediaMetadata.value?.id == mediaId ||
                player.currentMediaItem?.mediaId == mediaId
            if (!isCurrentTrack()) return@launch
            val revision = ++playbackLabelRevision
            val published = withContext(Dispatchers.IO) {
                mediaId to labelWithDeviceFileAttribute(mediaId, label)
            }
            if (revision != playbackLabelRevision || !isCurrentTrack()) return@launch
            if (lastPublishedPlaybackClient == published) return@launch
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "playback label mediaId=$mediaId -> ${published.second}",
            )
            lastPublishedPlaybackClient = published
            activePlaybackClientLabel.value = published.second
        }
    }
''' + s[z:]
a = s.index('        if (cachedPlaybackUrl(mediaId) != null) return', s.index('    private fun startPlaybackUrlPrefetch('))
z = s.index('        lateinit var deferred:', a)
s = s[:a] + '''        // Only register the existing single-flight job here. Disk evidence is checked on IO
        // inside its semaphore, never in the player callback that requests lookahead.
''' + s[z:]
s = once(s, '                        playbackUrlPrefetchSemaphore.withPermit { resolveAndCachePlaybackUrl(mediaId) }', '''                        playbackUrlPrefetchSemaphore.withPermit {
                            app.hush.music.polaris.PolarisLocalPrefetchGate.lookup(
                                cached = { cachedPlaybackUrl(mediaId, publishLabel = false) },
                                needsFetch = {
                                    LocalPlaybackLookahead.needsFetch(
                                        localPlaybackEvidence(mediaId, includeStreamingCache = false),
                                    )
                                },
                                resolve = { resolveAndCachePlaybackUrl(mediaId) },
                            )
                        }''')
a = s.index('    private suspend fun warmPlaybackUrl('); z = s.index('\n    /**', a)
block = s[a:z]; block = once(block, 'cachedPlaybackUrl(mediaId)', 'cachedPlaybackUrl(mediaId, publishLabel = false)')
s = s[:a] + block + s[z:]; p.write_text(s)
for f in (here/'resources13').glob('*.kt'):
    dest = r/'app/src/test/kotlin/app/hush/music/polaris'/f.name if f.name.endswith('Test.kt') else b/'polaris'/f.name
    dest.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(f, dest)
p = r/'app/build.gradle.kts'; s = p.read_text()
s = once(s, 'versionCode = 100574', 'versionCode = 100575')
s = once(s, 'versionName = "50.12"', 'versionName = "50.13"'); p.write_text(s)
print('50.13 disabled updater and background local evidence fixes applied')
