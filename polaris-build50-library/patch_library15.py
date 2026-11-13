from pathlib import Path
import sys, shutil
r=Path(sys.argv[1]); h=Path(__file__).resolve().parent; b=r/'app/src/main/kotlin/app/hush/music'; c=r/'core/src/main/kotlin/app/hush/music/innertube'
def once(s,a,z):
    assert s.count(a)==1,(a[:100],s.count(a));return s.replace(a,z,1)
for f in (h/'resources15').glob('*.kt'):
    dest=(r/'app/src/test/kotlin/app/hush/music/polaris'/f.name if f.name.endswith('Test.kt') else c/f.name if f.name=='StreamRequestBudget.kt' else b/'polaris'/f.name)
    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
# Never persist a visitor fallback over the account selected by the user.
p=b/'utils/PlaybackAuthPreferences.kt';s=p.read_text();a=s.index('suspend fun <T> Context.retryWithoutPlaybackLoginContext');z=s.index('private suspend fun Context.persistPlaybackAuthRepair',a)
s=s[:a]+'''suspend fun <T> Context.retryWithoutPlaybackLoginContext(block: suspend () -> Result<T>): Result<T> {
    // A client-specific 400 must not erase the selected account, even when retrying fails.
    return block()
}

'''+s[z:]
s=once(s,'repairedAuthState = repairedAuthState.copy(dataSyncId = refreshedDataSyncId).normalized()', 'repairedAuthState = repairedAuthState.copy(dataSyncId = storedAuthState.dataSyncId ?: refreshedDataSyncId).normalized()')
p.write_text(s)
# Preserve saved/explicit channel selection when the account endpoint returns a default channel.
p=b/'auth/YouTubeLoginRepository.kt';s=p.read_text();s=once(s,'return networkDataSyncId\n                ?: candidate.normalizeDataSyncId()', 'return candidate.normalizeDataSyncId()\n                ?: networkDataSyncId');p.write_text(s)
# Playlist mutation failures cannot change the global channel used by likes/browse/playback.
p=c/'YouTube.kt';s=p.read_text();a=s.index('    private suspend fun <T> withPlaylistMutationAuthRecovery');z=s.index('    private fun shouldRetryPlaylistMutationWithoutDelegatedContext',a)
s=s[:a]+'''    private suspend fun <T> withPlaylistMutationAuthRecovery(block: suspend () -> T): T = block()

'''+s[z:];p.write_text(s)
# Share a physical request bound across all nested resolution attempts.
p=c/'PlaybackTrace.kt';s=p.read_text();s=once(s,'withContext(context) { block() }','withContext(context + StreamRequestBudget()) { block() }');p.write_text(s)
p=c/'InnerTube.kt';s=p.read_text();s=once(s,'            val response = httpClient.post(client.requestApiUrl("player")) {', '''            val budget = kotlin.coroutines.coroutineContext[StreamRequestBudget]
            val requestNumber = budget?.acquire()
            if (requestNumber != null) PlaybackTrace.emit("player-wire-budget request=$request used=$requestNumber limit=${budget.limit}")
            val response = httpClient.post(client.requestApiUrl("player")) {''');p.write_text(s)
p=b/'utils/YTPlayerUtils.kt';s=p.read_text();s=once(s,'import app.hush.music.innertube.PlaybackAuthState','import app.hush.music.innertube.PlaybackAuthState\nimport app.hush.music.innertube.PlaybackTrace\nimport app.hush.music.innertube.StreamRequestBudgetExceeded')
# Session reset refreshes visitor metadata only; never removes or silently replaces account identity.
a=s.index('    suspend fun recoverFromBadStreamPlayerResponse');z=s.index('    private suspend fun ensureVisitorDataReady',a)
s=s[:a]+'''    suspend fun recoverFromBadStreamPlayerResponse(videoId: String) {
        val initial = YouTube.currentPlaybackAuthState()
        val refreshed = ensureVisitorDataReady(videoId, initial, forceRefresh = true, reason = "stream recovery")
        val current = YouTube.currentPlaybackAuthState()
        if (current.cookie == initial.cookie && current.dataSyncId == initial.dataSyncId) {
            YouTube.authState = current.copy(visitorData = refreshed.visitorData).normalized()
        }
        PlaybackTrace.emit("auth-recovery reason=stream-failure accountPreserved=true")
        clearPlaybackAuthCaches()
    }

'''+s[z:]
# Bot detection may refresh tokens/visitor, not select a different account channel.
a=s.index('        if (authState.hasLoginCookie) {',s.index('    private suspend fun repairAuthStateAfterBotDetection'));z=s.index('        if (\n            repairedAuthState.visitorData',a)
s=s[:a]+'''        // The user's selected channel remains fixed. Account refresh is explicit.
'''+s[z:]
# Try the authenticated music client first even when its WebView token is still warming.
s=once(s,'''        val metadataClient =
            resolveMetadataClient(''', '''        val metadataClient = if (canUseLoggedInPlayback) MAIN_CLIENT else
            resolveMetadataClient(''')
s=s.replace('val tokenResult = BotGuardTokenGenerator.mintToken(videoId, sessionId)', 'val tokenResult = kotlinx.coroutines.withTimeoutOrNull(12_000L) { BotGuardTokenGenerator.mintToken(videoId, sessionId) }', 1)
# Metadata 400 is not a license to downgrade all clients or persist a null channel.
a=s.index('        val metadataFailure = metadataResult.exceptionOrNull()');z=s.index('        var metadataPlayerResponse =',a)
s=s[:a]+'''        val metadataFailure = metadataResult.exceptionOrNull()
        if (metadataFailure != null) {
            PlaybackTrace.emit("auth-recovery reason=metadata-failure accountPreserved=true")
        }
'''+s[z:]
# A native client parameter error is local to that client: continue to other clients with same account.
a=s.index('    private fun Result<PlayerResponse>.getPlaybackPlayerResponseOrNull');z=s.index('    private fun throwInvalidPlaybackLoginContextIfNeeded',a)
s=s[:a]+'''    private fun Result<PlayerResponse>.getPlaybackPlayerResponseOrNull(
        videoId: String,
        authState: PlaybackAuthState,
    ): PlayerResponse? {
        val failure = exceptionOrNull()
        if (failure != null) {
            StreamResolutionControl.rethrowCancellation(failure)
            if (failure is StreamRequestBudgetExceeded) throw failure
            PlaybackTrace.emit("player-client-failure id=${PlaybackTrace.id(videoId)} error=${PlaybackTrace.label(failure.javaClass.simpleName)} accountPreserved=true")
            return null
        }
        return getOrNull()
    }

'''+s[z:]
# Preserve explicit login failure for metadata; budget exhaustion must not become another quality sweep.
s=s.replace('lastError?.let(StreamResolutionControl::rethrowCancellation)', '''lastError?.let(StreamResolutionControl::rethrowCancellation)
                if (lastError is StreamRequestBudgetExceeded) throw lastError!!''')
needle='''                (lastError as? StreamCandidateException)?.let { throw it }
                if ('''
s=once(s,needle,'''                (lastError as? StreamCandidateException)?.let { throw it }
                if (lastError is StreamAddressChallengedException || lastError is InvalidPlaybackLoginContextException) break
                if (''')
# Mint once for each newly needed session/client; otherwise web candidates had no player/GVS token.
s=once(s,'        var didRepairAuthAfterBotDetection = false','''        var didRepairAuthAfterBotDetection = false
        var didMintFallbackWebToken = metadataClientPlaceholder15''') if False else s
# metadata mint already covers logged-in WEB_REMIX. Anonymous paths also try token once before web fallback.
s=once(s,'        for ((index, candidateClient) in streamClients.withIndex()) {', '''        var didMintFallbackWebToken = metadataClient.useWebPoTokens
        for ((index, candidateClient) in streamClients.withIndex()) {''')
s=once(s,'            streamPlayerResponse =\n                if (client == metadataClient) {', '''            if (client.useWebPoTokens && !didMintFallbackWebToken && authState.sessionId != null) {
                didMintFallbackWebToken = true
                val minted = kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    BotGuardTokenGenerator.mintToken(videoId, authState.sessionId!!)
                }
                if (minted != null) authState = authState.withMintedWebPoTokens(authState.sessionId!!, minted.playerToken, minted.sessionToken)
            }
            streamPlayerResponse =
                if (client == metadataClient) {''')
p.write_text(s)
# Likes: preserve old membership/date. Only an explicit local unlike can remove an existing like.
p=b/'utils/SyncUtils.kt';s=p.read_text();a=s.index('                        if (authoritative || remoteIsExplicitlyEmpty) {',s.index('suspend fun syncLikedSongs'));z=s.index('                        val baseTimestamp',a)
s=s[:a]+'''                        app.hush.music.polaris.PolarisErrorReport.record("like-sync remote=${remoteSongs.size} localBefore=${database.likedSongsByNameAsc().first().size} preserveMissing=true authoritative=$authoritative")
'''+s[z:]
s=once(s,'''                                        } else if (!dbSong.song.liked || dbSong.song.likedDate != timestamp) {
                                            update(dbSong.song.copy(liked = true, likedDate = timestamp))''','''                                        } else if (!dbSong.song.liked || dbSong.song.likedDate == null) {
                                            update(dbSong.song.copy(liked = true, likedDate = app.hush.music.polaris.PolarisLikedSnapshotPolicy.likedDate(dbSong.song.likedDate, timestamp)))''')
s=once(s,'''                                    val dbSong = database.song(song.id).firstOrNull()
                                    database.withTransaction {
                                        if (!isSyncStillEnabled(gen)) return@withTransaction
                                        if (polarisLikeIntents.desired''','''                                    database.withTransaction {
                                        if (!isSyncStillEnabled(gen) || polarisLikeAccount() != likeAccount) return@withTransaction
                                        val dbSong = getSongByIdBlocking(song.id)
                                        if (polarisLikeIntents.desired''')
# Use policy in the real write path so its membership invariants are regression-tested.
s=once(s,'''                                        if (polarisLikeIntents.desired(likeAccount, song.id, likeSnapshotStartedAt) == false) {''','''                                        val desired = polarisLikeIntents.desired(likeAccount, song.id, likeSnapshotStartedAt)
                                        if (!app.hush.music.polaris.PolarisLikedSnapshotPolicy.likedAfterSnapshot(dbSong?.song?.liked == true, true, desired)) {''')
p.write_text(s)
# Do not redact diagnostic booleans named loginCookie/cookieHeader/poToken as credentials.
p=b/'polaris/PolarisSourcePolicy.kt';s=p.read_text();s=once(s,'(?i)(authorization|cookie|set-cookie)', '(?i)(?<![A-Za-z0-9_])(authorization|cookie|set-cookie)');s=once(s,'(?i)(access_token|refresh_token|visitor_data|po_token|token|signature|password)', '(?i)(?<![A-Za-z0-9_])(access_token|refresh_token|visitor_data|po_token|token|signature|password)');p.write_text(s)
# Surface the real cause even when the sweep wrapper surrounds login-required.
p=b/'playback/MusicService.kt';s=p.read_text();s=once(s,'        when {\n            throwable is app.hush.music.innertube.StreamCandidateException', '''        when {
            throwable is app.hush.music.innertube.StreamRequestBudgetExceeded -> PlaybackException(throwable.message, throwable, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            throwable is app.hush.music.utils.StreamAddressChallengedException -> mapStreamResolutionFailure(throwable.underlying, mediaId)
            throwable is app.hush.music.innertube.StreamCandidateException''');p.write_text(s)
p=r/'app/build.gradle.kts';s=p.read_text().replace('versionName = "50.14"','versionName = "50.15"').replace('versionCode = 100576','versionCode = 100577');p.write_text(s)
print('50.15 preserves account identity, bounds physical requests and protects existing likes')
