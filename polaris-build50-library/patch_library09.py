#!/usr/bin/env python3
"""50.9 diagnostic-only observation of existing resolver and HTTP request builders."""
from pathlib import Path
import sys, shutil
r=Path(sys.argv[1]);here=Path(__file__).resolve().parent
b=r/'app/src/main/kotlin/app/hush/music';core=r/'core/src/main/kotlin/app/hush/music/innertube'
def once(s,old,new,label):
 assert s.count(old)==1,(label,s.count(old));return s.replace(old,new,1)
def wrap_block(s, marker, expression):
 start=s.index(marker); opening=s.index('{',start)
 # Existing functions are delimited by the following same-indent member, not brace parsing of interpolated strings.
 end=s.index('\n    }',opening)
 body=s[opening+1:end]
 body=body.replace('return ', 'return@resolution ')
 return s[:opening+1]+'\n        return '+expression+' {\n'+body+'\n        }'+s[end:]
for name,target in [('PlaybackTrace.kt',core),('PolarisStreamDiagnostics.kt',b/'polaris')]:
 target.mkdir(parents=True,exist_ok=True);shutil.copyfile(here/'diagnostics09'/name,target/name)
# Capture the actual request after the existing header builder; keep retries and fallback identical.
p=core/'InnerTube.kt';s=p.read_text()
old='''    ) = httpClient.post(client.requestApiUrl("player")) {
        ytClient(client, setLogin = setLogin, authState = authState)'''
new='''    ): io.ktor.client.statement.HttpResponse {
        val request = kotlin.coroutines.coroutineContext[PlaybackTrace.Call]?.request ?: "unscoped"
        val wire = PlaybackTrace.nextId()
        val start = System.nanoTime()
        try {
            val response = httpClient.post(client.requestApiUrl("player")) {
        ytClient(client, setLogin = setLogin, authState = authState)
        PlaybackTrace.emit("player-wire-start request=$request wire=$wire client=${PlaybackTrace.label(client.clientName)} requested=${PlaybackTrace.id(videoId)} setLogin=$setLogin cookieHeader=${!headers[\"cookie\"].isNullOrBlank()} authorizationHeader=${!headers[\"Authorization\"].isNullOrBlank()} visitorHeader=${!headers[\"X-Goog-Visitor-Id\"].isNullOrBlank()} dataSyncBody=$includeDataSyncId poTokenBody=${poToken != null}")'''
s=once(s,old,new,'wire headers')
marker='''    private fun shouldRetryPlayerRequestWithoutDataSyncId('''
idx=s.index(marker);before=s[:idx];after=s[idx:]
old='''        )
    }

'''
end=before.rfind(old);assert end!=-1
new='''        )
            }
            PlaybackTrace.emit("player-wire-end request=$request wire=$wire http=${response.status.value} elapsedMs=${PlaybackTrace.elapsed(start)}")
            return response
        } catch (error: Throwable) {
            val code = (error as? io.ktor.client.plugins.ResponseException)?.response?.status?.value
            PlaybackTrace.emit("player-wire-end request=$request wire=$wire http=${code ?: -1} outcome=${if (error is kotlinx.coroutines.CancellationException) "cancelled-or-deadline" else "failure"} error=${PlaybackTrace.label(error.javaClass.simpleName)} elapsedMs=${PlaybackTrace.elapsed(start)}")
            throw error
        }
    }

'''
s=before[:end]+new+before[end+len(old):]+after
s=once(s,'            ytClient(client, setLogin = setLogin || useLoginForBrowse)','''            ytClient(client, setLogin = setLogin || useLoginForBrowse)
            PlaybackTrace.browse(client.clientName, currentAuthState(), setLogin || useLoginForBrowse,
                !headers["cookie"].isNullOrBlank(), !headers["Authorization"].isNullOrBlank(),
                (setLogin || useLoginForBrowse) && !dataSyncId.isNullOrBlank())''','browse auth comparison')
p.write_text(s)
p=core/'YouTube.kt';s=p.read_text();start=s.index('    suspend fun player(');end=s.index('    suspend fun registerPlayback(',start);block=s[start:end]
block=once(block,'            innerTube\n                .player(','''            PlaybackTrace.player(videoId, client.clientName, client.loginSupported, setLogin,
                authState, currentPlaybackAuthState(), resolvedPoToken != null) { request ->
            val response = innerTube
                .player(''','logical request span')
block=once(block,'                ).body<PlayerResponse>()','''                ).body<PlayerResponse>()
            val audio = response.streamingData?.adaptiveFormats.orEmpty().filter { it.isAudio }
            PlaybackTrace.response(request, videoId, response.videoDetails?.videoId,
                response.playabilityStatus.status, response.playabilityStatus.reason,
                audio.size, audio.count { !it.url.isNullOrBlank() },
                audio.count { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() })
            response
            }''','decoded response observation');s=s[:start]+block+s[end:];p.write_text(s)
# Keep each resolver's intent with its children across IO threads; a shared request is labeled shared.
p=b/'playback/MusicService.kt';s=p.read_text()
s=wrap_block(s,'    private suspend fun resolveAndCachePlaybackUrlInternal(', 'app.hush.music.innertube.PlaybackTrace.resolution(mediaId, "playback-shared")')
s=once(s, '        // All non-local, non-extractor playback uses the same single-flight resolver', '        app.hush.music.innertube.PlaybackTrace.emit("playback-route id=${app.hush.music.innertube.PlaybackTrace.id(mediaId)} route=stream-resolution reason=no-usable-local-file-or-cache")\n        // All non-local, non-extractor playback uses the same single-flight resolver', 'explicit stream fallback')
# Mark local returns too, without inspecting or logging the file path.
s=once(s,'        ownedLocalPlaybackFile(mediaId)?.let { local ->','''        ownedLocalPlaybackFile(mediaId)?.let { local ->
            app.hush.music.innertube.PlaybackTrace.emit("resolution-local id=${app.hush.music.innertube.PlaybackTrace.id(mediaId)} bytes=${local.length()}")''','local route result')
p.write_text(s)
# Include provider lookup and transfer in the same download attempt context, including pre-YouTube timeouts.
p=b/'playback/DownloadUtil.kt';s=p.read_text();start=s.index('        private suspend fun downloadToFile(');end=s.index('        private fun claimTargetFile(',start);block=s[start:end]
block=once(block, '            withContext(Dispatchers.IO) {', '            app.hush.music.innertube.PlaybackTrace.resolution(mediaId, "download-file") {\n            withContext(Dispatchers.IO) {', 'download outer observation')
block=block.rstrip()+'\n            }\n\n'
block=once(block, '                recordDownloadProgress(mediaId, "주소 조회")', '                app.hush.music.innertube.PlaybackTrace.stage("download-provider-start", mediaId)\n                recordDownloadProgress(mediaId, "주소 조회")', 'provider begins')
block=once(block, '                }?.takeIf { it.scheme == "file" }?.path?.let { path ->', '                }.also { app.hush.music.innertube.PlaybackTrace.stage("download-provider-end", mediaId) }?.takeIf { it.scheme == "file" }?.path?.let { path ->', 'provider ends')
s=s[:start]+block+s[end:];p.write_text(s)
# Actual opened data source result (no URL or filename).
p=b/'polaris/PolarisTraceDataSource.kt';s=p.read_text();s=once(s, '            delegate.open(dataSpec).also { length ->', '\n            delegate.open(dataSpec).also { length ->\n                if (route == "hush-original") app.hush.music.innertube.PlaybackTrace.emit("playback-opened id=${app.hush.music.innertube.PlaybackTrace.id(dataSpec.key)} scheme=${app.hush.music.innertube.PlaybackTrace.label(delegate.uri?.scheme ?: "cache-or-unknown")} bytes=$length")', 'opened local or remote result');p.write_text(s)
# All download call sites share this entry. Preserve its Result return / labeled return behavior.
p=b/'utils/YTPlayerUtils.kt';s=p.read_text();start=s.index('    suspend fun playerResponseForDownload(');end=s.index('    private val downloadPreferredStreamClientAttempts:',start);block=s[start:end]
block=once(block,'        runCatching {','''        runCatching {
        app.hush.music.innertube.PlaybackTrace.resolution(videoId, "download") {''','download resolution context')
block=block.replace('return@runCatching attemptResult.getOrThrow()', 'return@resolution attemptResult.getOrThrow()')
block=block.rstrip()+'\n        }\n\n';s=s[:start]+block+s[end:]
# Record source choices / no-stream versus failed URL validation without URL values.
start=s.index('    private suspend fun playerResponseForPlaybackOnce(')
prefix=s[:start]; body=s[start:]
body=once(body, '        val signatureTimestamp = getSignatureTimestampOrNull(videoId)', '\n        app.hush.music.innertube.PlaybackTrace.stage("signature-start", videoId)\n        val signatureTimestamp = getSignatureTimestampOrNull(videoId)\n        app.hush.music.innertube.PlaybackTrace.stage("signature-end", videoId)', 'bootstrap timing') if body.count('        val signatureTimestamp = getSignatureTimestampOrNull(videoId)') == 1 else body.replace('        val signatureTimestamp = getSignatureTimestampOrNull(videoId)', '        app.hush.music.innertube.PlaybackTrace.stage("signature-start", videoId)\n        val signatureTimestamp = getSignatureTimestampOrNull(videoId)\n        app.hush.music.innertube.PlaybackTrace.stage("signature-end", videoId)',1)
s=prefix+body
s=once(s, '        var metadataResult =', '        app.hush.music.innertube.PlaybackTrace.stage("metadata-start", videoId)\n        var metadataResult =', 'metadata timing')
s=once(s, '        val streamClients =', '        app.hush.music.innertube.PlaybackTrace.stage("stream-clients", videoId)\n        val streamClients =', 'fallback timing')
s=once(s, '            val valid =\n                validateStatus(', '            app.hush.music.innertube.PlaybackTrace.stage("url-probe", videoId)\n            val valid =\n                validateStatus(', 'probe timing')
s=once(s,'            if (candidates.isEmpty()) continue','''            app.hush.music.innertube.PlaybackTrace.emit("stream-candidates id=${app.hush.music.innertube.PlaybackTrace.id(videoId)} client=${app.hush.music.innertube.PlaybackTrace.label(client.clientName)} count=${candidates.size}")
            if (candidates.isEmpty()) continue''','candidate result')
s=once(s,'            if (valid) {','''            app.hush.music.innertube.PlaybackTrace.emit("stream-probe id=${app.hush.music.innertube.PlaybackTrace.id(videoId)} client=${app.hush.music.innertube.PlaybackTrace.label(client.clientName)} valid=$valid")
            if (valid) {''','probe result')
p.write_text(s)
# Install before browse / playback, not only after a MusicService request.
p=b/'App.kt';s=p.read_text();s=once(s,'        if (currentProcessName()?.endsWith(":polaris_verify") == true) return','''        if (currentProcessName()?.endsWith(":polaris_verify") == true) return
        app.hush.music.polaris.PolarisStreamDiagnostics.install(this)''','early diagnostic installation');p.write_text(s)
p=b/'polaris/PolarisErrorReport.kt';s=p.read_text();s=once(s,'            appendLine("=== LAST ERROR ===")','''            appendLine("=== STREAM REQUEST DIAGNOSTICS (50.9) ===")
            appendLine("Auth flags describe request construction, not server authentication success. reason is categorized; no credential values. attempt/request/wire correlate retries. purpose=playback-shared may serve prefetch and playback. HTTP 200 does not imply playability OK.")
            appendLine(PolarisStreamDiagnostics.dump(context))
            appendLine("=== LAST ERROR ===")''','dedicated diagnostics section');p.write_text(s)
p=r/'app/build.gradle.kts';s=p.read_text().replace('versionCode = 100570','versionCode = 100571').replace('versionName = "50.8"','versionName = "50.9"');p.write_text(s)
# Unit test in app so the existing regression command executes the production core observer.
shutil.copyfile(here/'diagnostics09/PolarisStreamDiagnosticsTest.kt',r/'app/src/test/kotlin/app/hush/music/polaris/PolarisStreamDiagnosticsTest.kt')
print('50.9 structured stream diagnostics applied; no client order, authentication or retry policy changed')
