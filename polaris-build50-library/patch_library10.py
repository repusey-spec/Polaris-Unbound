#!/usr/bin/env python3
"""50.10: preserve request authentication, propagate cancellation, identify local URL failures."""
from pathlib import Path
import shutil, sys
r=Path(sys.argv[1]); here=Path(__file__).resolve().parent
b=r/'app/src/main/kotlin/app/hush/music'; c=r/'core/src/main/kotlin/app/hush/music/innertube'
def once(s,old,new,label):
    assert s.count(old)==1,(label,s.count(old)); return s.replace(old,new,1)
shutil.copyfile(here/'resolver10/StreamResolutionControl.kt',c/'StreamResolutionControl.kt')
p=c/'InnerTube.kt'; s=p.read_text()
start=s.index('    suspend fun player('); end=s.index('    private suspend fun executePlayerRequest(',start)
block=s[start:end]
block=once(block,'                setLogin = false,','''                // Only the rejected context field changes. Keep the caller's cookie/auth choice.
                setLogin = setLogin,''','retain login for context retry')
block=once(block,'        } catch (failure: Throwable) {','''        } catch (failure: Throwable) {
            StreamResolutionControl.rethrowCancellation(failure)''','cancel before retry')
s=s[:start]+block+s[end:]; p.write_text(s)
p=b/'utils/YTPlayerUtils.kt'; s=p.read_text()
s=once(s,'import kotlinx.coroutines.async','''import app.hush.music.innertube.StreamResolutionControl
import app.hush.music.innertube.StreamCandidateException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async''','imports')
# Scope Result changes to the playback/download resolver, preserving public Result<T> APIs.
start=s.index('suspend fun playerResponseForPlayback('); end=s.index('    private val downloadPreferredStreamClientAttempts:',start)
block=s[start:end].replace('runCatching {','StreamResolutionControl.attempt {').replace('return@runCatching','return@attempt')
for line in ['        while (true) {','            for (attempt in attemptQualities) {','            for (preferredStreamClient in downloadPreferredStreamClientAttempts) {']:
    block=once(block,line,line+'\n'+(' '*(len(line)-len(line.lstrip())+4))+'currentCoroutineContext().ensureActive()',line)
block=block.replace('lastError = attemptResult.exceptionOrNull()','''lastError = attemptResult.exceptionOrNull()
                lastError?.let(StreamResolutionControl::rethrowCancellation)
                (lastError as? StreamCandidateException)?.let { throw it }''')
block=once(block,'lastError = rotatedAttemptResult.exceptionOrNull()','''lastError = rotatedAttemptResult.exceptionOrNull()
                    lastError?.let(StreamResolutionControl::rethrowCancellation)
                    (lastError as? StreamCandidateException)?.let { throw it }''','rotated cancellation')
s=s[:start]+block+s[end:]
# Token generation and auxiliary calls must also stop when their parent is cancelled.
s=s.replace('catch (e: Exception) {','catch (e: Exception) {\n                    StreamResolutionControl.rethrowCancellation(e)')
s=once(s,'        for ((index, candidateClient) in streamClients.withIndex()) {','''        var localCandidateFailure: StreamCandidateException? = null
        for ((index, candidateClient) in streamClients.withIndex()) {
            currentCoroutineContext().ensureActive()''','client checkpoint')
s=once(s,'            for (candidate in candidates) {','''            val gvsReady = !authState.resolveGvsPoToken(client).isNullOrBlank()
            fun candidateEvent(candidate: PlayerResponse.StreamingData.Format, outcome: String, error: Throwable? = null) =
                StreamResolutionControl.candidate(videoId, client.clientName, candidate.itag, outcome, gvsReady, error)
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()''','candidate checkpoint')
s=once(s,'                if (expectedDurationMs != null && isLikelyPreview(candidate, expectedDurationMs)) {','''                if (expectedDurationMs != null && isLikelyPreview(candidate, expectedDurationMs)) {
                    candidateEvent(candidate, "preview")''','preview reason')
s=once(s,'                if (shouldSkipCipheredWebCandidate(client, candidate, authState)) continue','''                if (shouldSkipCipheredWebCandidate(client, candidate, authState)) {
                    candidateEvent(candidate, "missing-gvs-token")
                    localCandidateFailure = StreamCandidateException("missing-gvs-token")
                    continue
                }''','gvs reason')
s=once(s,'                if (isCipheredFormat(candidate) && !StreamClientAvailability.canDecipher()) {','''                if (isCipheredFormat(candidate) && !StreamClientAvailability.canDecipher()) {
                    candidateEvent(candidate, "decipher-cooldown")
                    if (localCandidateFailure == null) localCandidateFailure = StreamCandidateException("decipher-cooldown")''','cooldown reason')
s=once(s,'''                        cached.url
                    } else {
                        findUrlOrNull(candidate, videoId, client, authState)
                    } ?: continue''','''                        candidateEvent(candidate, "cache-hit")
                        cached.url
                    } else {
                        candidateEvent(candidate, "conversion-start")
                        findUrlOrNull(candidate, videoId, client, authState) { error ->
                            candidateEvent(candidate, "conversion-failed", error)
                            localCandidateFailure = StreamCandidateException(StreamResolutionControl.category(error), error)
                        }.also { if (it != null) candidateEvent(candidate, "conversion-ready") }
                    } ?: continue''','conversion outcome')
s=once(s,'                selectedFormat = candidate','''                candidateEvent(candidate, "selected")
                selectedFormat = candidate''','selection reason')
s=once(s,'        if (streamPlayerResponse == null) {\n            // "All clients failed"','''        if (streamPlayerResponse == null) {
            currentCoroutineContext().ensureActive()
            // A later anonymous-client refusal does not explain an earlier OK/cipher response.
            localCandidateFailure?.let { throw it }
            // "All clients failed"''','local failure precedence')
s=once(s,'    private fun getSignatureTimestampOrNull(videoId: String): Int? {','''    private suspend fun getSignatureTimestampOrNull(videoId: String): Int? =
        runInterruptible(Dispatchers.IO) { getSignatureTimestampBlocking(videoId) }

    private fun getSignatureTimestampBlocking(videoId: String): Int? {''','interruptible bootstrap')
s=once(s,'    private fun findUrlOrNull(','    private suspend fun findUrlOrNull(','interruptible converter signature')
start=s.index('    private suspend fun findUrlOrNull(');end=s.index('    private fun Throwable.isJavaScriptPlayerExtractorFailure()',start)
block=s[start:end]
block=once(block,'''        authState: PlaybackAuthState,
    ): String? {''','''        authState: PlaybackAuthState,
        onFailure: (Throwable) -> Unit,
    ): String? {
        currentCoroutineContext().ensureActive()''','converter callback')
block=once(block,'''            NewPipeUtils
                .getStreamUrl(format, videoId, client, authState)''','''            runInterruptible(Dispatchers.IO) {
                NewPipeUtils.getStreamUrl(format, videoId, client, authState)
            }''','interruptible conversion')
block=once(block,'                .onFailure {','''                .onFailure {
                    StreamResolutionControl.rethrowCancellation(it)
                    currentCoroutineContext().ensureActive()
                    onFailure(it)''','converter cancellation first')
s=s[:start]+block+s[end:]
# Both nullable and throwing response adapters encounter Result-returning YouTube APIs.
s=s.replace('''        val failure = exceptionOrNull()
        if (failure != null) {''','''        val failure = exceptionOrNull()
        if (failure != null) {
            StreamResolutionControl.rethrowCancellation(failure)''')
p.write_text(s)
p=b/'playback/MusicService.kt';s=p.read_text();start=s.index('    private fun mapStreamResolutionFailure(')
end=s.index('    private fun resolveCachedDataSpec(',start)
block=s[start:end];block=once(block,'        when {','''        when {
            throwable is app.hush.music.innertube.StreamCandidateException -> {
                PlaybackException(throwable.message, throwable, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            }
''','playback candidate message');s=s[:start]+block+s[end:];p.write_text(s)
p=c/'PlaybackTrace.kt'; s=p.read_text().replace('import kotlinx.coroutines.withContext','import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.ensureActive')
s=once(s,'    suspend fun stage(name: String, videoId: String) {','''    suspend fun stage(name: String, videoId: String) {
        coroutineContext.ensureActive()''','stage checkpoint');p.write_text(s)
# Record signature vs n-parameter failures inside the actual transform, no URL or exception text.
p=c/'pages/NewPipe.kt';s=p.read_text()
s=once(s,'''                    url.parameters[signatureParam] =
                        deobfuscateSignature(videoId, obfuscatedSignature)''','''                    app.hush.music.innertube.PlaybackTrace.emit("stream-transform id=${app.hush.music.innertube.PlaybackTrace.id(videoId)} stage=signature-start")
                    url.parameters[signatureParam] =
                        deobfuscateSignature(videoId, obfuscatedSignature)
                    app.hush.music.innertube.PlaybackTrace.emit("stream-transform id=${app.hush.music.innertube.PlaybackTrace.id(videoId)} stage=signature-ready")''','signature stage')
start=s.index('    fun getStreamUrl(');end=s.index('    private fun deobfuscateSignature(',start);block=s[start:end]
block=block.replace('}.getOrElse { directUrl }','''}.getOrElse {
                            app.hush.music.innertube.StreamResolutionControl.rethrowCancellation(it)
                            app.hush.music.innertube.PlaybackTrace.emit("stream-transform id=${app.hush.music.innertube.PlaybackTrace.id(videoId)} stage=n-parameter-failed category=${app.hush.music.innertube.StreamResolutionControl.category(it)}")
                            directUrl
                        }''')
block=block.replace('}.getOrElse { url }','''}.getOrElse {
                    app.hush.music.innertube.StreamResolutionControl.rethrowCancellation(it)
                    app.hush.music.innertube.PlaybackTrace.emit("stream-transform id=${app.hush.music.innertube.PlaybackTrace.id(videoId)} stage=n-parameter-failed category=${app.hush.music.innertube.StreamResolutionControl.category(it)}")
                    url
                }''')
s=s[:start]+block+s[end:];p.write_text(s)
p=b/'polaris/PolarisDownloadPolicy.kt';s=p.read_text();s=once(s,'            causes.any { it is PolarisResolutionTimeout }','''            causes.filterIsInstance<app.hush.music.innertube.StreamCandidateException>().isNotEmpty() ->
                causes.filterIsInstance<app.hush.music.innertube.StreamCandidateException>().first().message.orEmpty()
            causes.any { it is PolarisResolutionTimeout }''','download message');p.write_text(s)
p=b/'polaris/PolarisErrorReport.kt';p.write_text(p.read_text().replace('DIAGNOSTICS (50.9)','DIAGNOSTICS (50.10)'))
p=r/'app/build.gradle.kts';p.write_text(p.read_text().replace('versionCode = 100571','versionCode = 100572').replace('versionName = "50.9"','versionName = "50.10"'))
for name in ['PolarisResolverCancellationTest.kt','PolarisPlayerContextRetryTest.kt']:
    shutil.copyfile(here/'resolver10'/name,r/'app/src/test/kotlin/app/hush/music/polaris'/name)
print('50.10 resolver cancellation, authenticated context retry and local candidate failure handling applied')
