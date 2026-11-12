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
    private val polarisLocalRoutes = app.hush.music.polaris.PolarisLocalRoutes()
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
            playbackUrlResolutionInFlight.entries.removeIf { (key, job) ->
                if (PlaybackResolutionKeys.mediaIdOf(key) == mediaId) { job.cancel(); true } else false
            }
            app.hush.music.polaris.PolarisErrorReport.record("local-recovery id=$mediaId route=youtube reason=missing-or-length-mismatch file=preserved")
        }
        if (mediaId in polarisDamagedLocalIds) {
            polarisLocalRoutes.openedRemote(mediaId)
            val remote = runBlocking(Dispatchers.IO) { resolveAndCachePlaybackUrl(mediaId) }
            return app.hush.music.polaris.PolarisOnlineRecovery.remoteSpec(dataSpec, mediaId, remote.url.toUri())
        }
''' +s[a:]
s=once(s,'            return dataSpec.withUri(downloaded.toUri())','            polarisLocalRoutes.openedFile(mediaId)\n            return dataSpec.withUri(downloaded.toUri())')
a=s.index('        val downloadedEntry =',s.index('        val unreadableStoredBytes ='));z=s.index('        // A track whose bytes came from our own SpotiFLAC',a)
s=s[:a]+'''        if (!isLocalMedia && polarisLocalRoutes.wasFile(currentMediaId) && unreadableStoredBytes &&
            currentMediaId !in polarisDamagedLocalIds && playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
            val resumePosition = player.currentPosition.coerceAtLeast(0L)
            val resumeIndex = player.currentMediaItemIndex
            if (polarisDamagedLocalIds.size >= 64) polarisDamagedLocalIds.clear()
            polarisDamagedLocalIds.add(currentMediaId)
            polarisLocalRoutes.openedRemote(currentMediaId)
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
# Metadata recovery may finish after a user toggles the heart: update only the latest row.
s=once(s, '        database.query {\n            if (song == null) {\n                insert(mediaMetadata.copy(duration = duration))\n            } else if (song.song.duration == -1) {\n                update(song.song.copy(duration = duration))', '        database.transaction {\n            val latest = getSongByIdBlocking(mediaId)?.song\n            if (latest == null) {\n                insert(mediaMetadata.copy(duration = duration))\n            } else if (latest.duration == -1) {\n                update(latest.copy(duration = duration))')
s=once(s, '                currentMediaMetadata.value = currentMediaMetadata.value?.copy(liked = updatedSong.liked)', '                if (currentMediaMetadata.value?.id == mediaId) currentMediaMetadata.value = currentMediaMetadata.value?.copy(liked = updatedSong.liked)')
# Quarantined files exist for repair, but cannot label or supply a recovered remote stream.
s=once(s, '        if (label.contains(PlaybackSourceLabels.DOWNLOADED, ignoreCase = true)) return label', '        if (mediaId in polarisDamagedLocalIds) return label\n        if (label.contains(PlaybackSourceLabels.DOWNLOADED, ignoreCase = true)) return label')
s=once(s, '        return playbackUrlCache[mediaId]?.takeIf { cached ->\n            cached.isValidFor(', '        return playbackUrlCache[mediaId]?.takeIf { cached ->\n            if (mediaId in polarisDamagedLocalIds && !cached.isYouTubeStream) return@takeIf false\n            cached.isValidFor(')
p.write_text(s)
p=b/'utils/SyncUtils.kt';s=p.read_text()
s=once(s,'        private val syncMutex = Mutex()', '''        private val polarisLikeIntents = app.hush.music.polaris.PolarisLikeIntents(context)
        private val polarisLikeRemoteMutex = Mutex()
        private fun polarisLikeAccount() = YouTube.dataSyncId?.takeIf { it.isNotBlank() }
            ?: YouTube.cookie.orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("SAPISID=") }?.substringAfter('=')
            ?: "local"
        private val syncMutex = Mutex()''')
s=once(s,'        fun likeSong(s: SongEntity) {\n            if (s.isLocal) return','''        fun likeSong(s: SongEntity) {
            if (s.isLocal) return
            val localAccount = polarisLikeAccount()
            polarisLikeIntents.record(localAccount, s.id, s.liked)''')
s=once(s,'                likeVideoWithRetry(s, gen)','''                polarisLikeRemoteMutex.withLock {
                    val desired = polarisLikeIntents.latest(localAccount, s.id)
                    if (polarisLikeAccount() != localAccount || (desired != null && desired != s.liked)) return@withLock Result.failure<io.ktor.client.statement.HttpResponse>(IllegalStateException("Superseded like intent"))
                    likeVideoWithRetry(s, gen).onSuccess { polarisLikeIntents.acknowledge(localAccount, s.id, s.liked) }
                }''')
s=s.replace('                                        if (dbSong == null) {','''                                        if (polarisLikeIntents.desired(likeAccount, song.id, likeSnapshotStartedAt) == false) {
                                            if (dbSong != null && dbSong.song.liked) update(dbSong.song.copy(liked = false, likedDate = null))
                                            return@withTransaction
                                        }
                                        if (dbSong == null) {''', 1)
# Never un-like a locally liked song from a stale absent remote snapshot.
s=once(s,'                                    .filterNot { it.id in remoteIds }','''                                    .filterNot { it.id in remoteIds }
                                    .filterNot { polarisLikeIntents.desired(likeAccount, it.id, likeSnapshotStartedAt) == true }''')
# Replay a bounded pending outbox before reading the remote liked snapshot.
a=s.index('        suspend fun syncLikedSongs(');z=s.index('        suspend fun syncLibrarySongs(',a)
block=s[a:z]
block=once(block, '                val gen = syncGeneration.get()\n                YouTube', '                val gen = syncGeneration.get()\n                val likeAccount = polarisLikeAccount()\n                polarisFlushPendingLikes(likeAccount, gen)\n                val likeSnapshotStartedAt = System.currentTimeMillis()\n                YouTube')
block=block.replace('                        if (!isSyncStillEnabled(gen)) return@onSuccess', '                        if (!isSyncStillEnabled(gen) || polarisLikeAccount() != likeAccount) return@onSuccess')
s=s[:a]+block+s[z:]
s=once(s, '        private suspend fun likeVideoWithRetry(', '''        private suspend fun polarisFlushPendingLikes(account: String, gen: Long) {
            for ((id, desired) in polarisLikeIntents.pending(account).take(20)) {
                if (!isSyncStillEnabled(gen) || polarisLikeAccount() != account) return
                val song = database.getSongById(id)?.song ?: continue
                polarisLikeRemoteMutex.withLock {
                    if (polarisLikeIntents.latest(account, id) == desired && polarisLikeAccount() == account) {
                        likeVideoWithRetry(song.copy(liked = desired), gen)
                            .onSuccess { polarisLikeIntents.acknowledge(account, id, desired) }
                    }
                }
            }
        }

        private suspend fun likeVideoWithRetry(''')
s=once(s, '            gen: Long,\n        ): Result<io.ktor.client.statement.HttpResponse>', '            gen: Long,\n            account: String = polarisLikeAccount(),\n        ): Result<io.ktor.client.statement.HttpResponse>')
s=once(s, '                if (!isSyncStillEnabled(gen)) return result', '                if (!isSyncStillEnabled(gen) || polarisLikeAccount() != account) return result')
a=s.index('                val targetVideoId =', s.index('        private suspend fun likeVideoWithRetry('))
z=s.index('                Timber.d(',a)
s=s[:a]+'                val targetVideoId = song.id\n'+s[z:]
p.write_text(s)
p=r/'app/build.gradle.kts';p.write_text(p.read_text().replace('versionName = "50.13"','versionName = "50.14"').replace('versionCode = 100575','versionCode = 100576'))
print('50.14 local-file YouTube recovery and persistent unlike protection applied')
