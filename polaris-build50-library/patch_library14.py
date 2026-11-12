from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]);h=Path(__file__).resolve().parent;b=r/'app/src/main/kotlin/app/hush/music'
def once(s,a,z):
 assert s.count(a)==1,(a[:80],s.count(a));return s.replace(a,z,1)
for f in (h/'resources14').glob('*.kt'):
 dest=r/'app/src/test/kotlin/app/hush/music/polaris'/f.name if f.name.endswith('Test.kt') else b/'polaris'/f.name
 shutil.copyfile(f,dest)
p=b/'playback/MusicService.kt';s=p.read_text()
s=once(s,'    private val forceYouTubeOnceMediaIds =','''    // Session-local quarantine: preserve the user's file, never feed its bytes to this retry.
    private val polarisDamagedLocalIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var polarisLastLocalId: String? = null
    private val forceYouTubeOnceMediaIds =''')
s=once(s,'        ownedLocalPlaybackFile(mediaId)?.let { local ->','        (if (mediaId in polarisDamagedLocalIds) null else ownedLocalPlaybackFile(mediaId))?.let { local ->')
s=once(s,'        val forceYouTubeOnce = mediaId in forceYouTubeOnceMediaIds','        val forceYouTubeOnce = mediaId in forceYouTubeOnceMediaIds || mediaId in polarisDamagedLocalIds')
# Bypass all local priority branches and use a separate cache key for recovered remote bytes.
a=s.index('        val knownContentLength',s.index('    private fun resolvePlaybackDataSpec('))
s=s[:a]+'''        if (mediaId !in polarisDamagedLocalIds && downloadedFileStore.get(mediaId) != null && downloadedFileStore.fileFor(mediaId) == null) {
            if (polarisDamagedLocalIds.size >= 64) polarisDamagedLocalIds.clear()
            polarisDamagedLocalIds.add(mediaId)
            playbackUrlCache.remove(mediaId)
            extractorPlaybackUrlCache.remove(mediaId)
            cancelPlaybackUrlPrefetch(mediaId)
            app.hush.music.polaris.PolarisErrorReport.record("local-recovery id=$mediaId route=youtube reason=missing-or-length-mismatch file=preserved")
        }
        if (mediaId in polarisDamagedLocalIds) {
            val remote = runBlocking(Dispatchers.IO) { resolveAndCachePlaybackUrl(mediaId) }
            return app.hush.music.polaris.PolarisOnlineRecovery.remoteSpec(dataSpec, mediaId, remote.url.toUri())
        }
''' +s[a:]
s=once(s,'            return dataSpec.withUri(downloaded.toUri())','            polarisLastLocalId = mediaId\n            return dataSpec.withUri(downloaded.toUri())')
a=s.index('        val downloadedEntry =',s.index('        val unreadableStoredBytes ='));z=s.index('        // A track whose bytes came from our own SpotiFLAC',a)
s=s[:a]+'''        if (!isLocalMedia && polarisLastLocalId == currentMediaId && unreadableStoredBytes &&
            currentMediaId !in polarisDamagedLocalIds && playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
            val resumePosition = player.currentPosition.coerceAtLeast(0L)
            val resumeIndex = player.currentMediaItemIndex
            if (polarisDamagedLocalIds.size >= 64) polarisDamagedLocalIds.clear()
            polarisDamagedLocalIds.add(currentMediaId)
            polarisLastLocalId = null
            cancelPlaybackUrlPrefetch(currentMediaId)
            playbackUrlResolutionInFlight.entries.removeIf { (key, job) ->
                if (PlaybackResolutionKeys.mediaIdOf(key) == currentMediaId) { job.cancel(); true } else false
            }
            playbackUrlCache.remove(currentMediaId)
            extractorPlaybackUrlCache.remove(currentMediaId)
            app.hush.music.polaris.PolarisErrorReport.record("local-recovery id=$currentMediaId route=youtube code=${error.errorCode} file=preserved")
            scope.launch {
                if (player.currentMediaItem?.mediaId != currentMediaId || player.currentMediaItemIndex != resumeIndex) return@launch
                player.seekTo(resumeIndex, resumePosition)
                player.prepare()
            }
            return
        }

''' +s[z:]
# Notify AA of every Room liked-state change, including context menus and sync.
s=once(s,'        setMediaNotificationProvider(','''        ioScope.launch {
            database.likedSongsByNameAsc().map { rows -> rows.map { it.song.id } }.distinctUntilChanged().collect { ids ->
                val count = ids.size
                withContext(Dispatchers.Main) {
                    mediaSession.notifyChildrenChanged("playlist/LP_LIKED", count + 1, null)
                    mediaSession.notifyChildrenChanged(LIKED, count, null)
                    mediaSession.notifyChildrenChanged("polaris:streaming", 3, null)
                }
            }
        }
        setMediaNotificationProvider(''')
p.write_text(s)
p=b/'utils/SyncUtils.kt';s=p.read_text()
s=once(s,'        private val syncMutex = Mutex()', '''        private val polarisLikeIntents = app.hush.music.polaris.PolarisLikeIntents(context)
        private val polarisLikeRemoteMutex = Mutex()
        private fun polarisLikeAccount() = YouTube.currentPlaybackAuthState().sessionId.orEmpty()
        private val syncMutex = Mutex()''')
s=once(s,'        fun likeSong(s: SongEntity) {\n            if (s.isLocal) return','''        fun likeSong(s: SongEntity) {
            if (s.isLocal) return
            polarisLikeIntents.record(polarisLikeAccount(), s.id, s.liked)''')
s=once(s,'                likeVideoWithRetry(s, gen)','''                polarisLikeRemoteMutex.withLock {
                    val desired = polarisLikeIntents.desired(polarisLikeAccount(), s.id)
                    if (desired != null && desired != s.liked) return@withLock Result.failure<io.ktor.client.statement.HttpResponse>(IllegalStateException("Superseded like intent"))
                    likeVideoWithRetry(s, gen)
                }''')
s=s.replace('                                        if (dbSong == null) {','''                                        if (polarisLikeIntents.desired(polarisLikeAccount(), song.id) == false) {
                                            if (dbSong != null && dbSong.song.liked) update(dbSong.song.copy(liked = false, likedDate = null))
                                            return@withTransaction
                                        }
                                        if (dbSong == null) {''', 1)
# Never un-like a locally liked song from a stale absent remote snapshot.
s=once(s,'                                    .filterNot { it.id in remoteIds }','''                                    .filterNot { it.id in remoteIds }
                                    .filterNot { polarisLikeIntents.desired(polarisLikeAccount(), it.id) == true }''')
p.write_text(s)
p=r/'app/build.gradle.kts';p.write_text(p.read_text().replace('versionName = "50.13"','versionName = "50.14"').replace('versionCode = 100575','versionCode = 100576'))
print('50.14 local-file YouTube recovery and persistent unlike protection applied')
