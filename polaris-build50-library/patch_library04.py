#!/usr/bin/env python3
"""Apply AFTER verified 50.3. No UI source selectors or Hush engines changed."""
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1]).resolve()
base = root / 'app/src/main/kotlin/app/hush/music'
overlay = Path(__file__).resolve().parent / 'diagnostics04'
polaris = base / 'polaris'

def once(source, old, new, label):
    assert source.count(old) == 1, f'{label}: {source.count(old)} anchors'
    return source.replace(old, new, 1)

for path in overlay.glob('*.kt'):
    if path.name.endswith('Test.kt'):
        target = root / 'app/src/test/kotlin/app/hush/music/polaris' / path.name
    else:
        target = polaris / path.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)

service = base / 'playback/MusicService.kt'
s = service.read_text()
s = once(s, '        super.onCreate()\n        ensureScopesActive()',
    '        super.onCreate()\n        app.hush.music.polaris.PolarisErrorReport.install(this)\n        ensureScopesActive()', 'report service init')
s = once(s, '        super.onPlayerError(error)\n',
    '        app.hush.music.polaris.PolarisErrorReport.failure("player", error, player.currentMediaItem)\n        super.onPlayerError(error)\n', 'persist both-source player cause chain')

# Restore the original Hush data-source factory, instead of a process-global radio switch.
start = s.index('        val radioHttpFactory =', s.index('    private fun createDataSourceFactory()'))
end = s.index('    private fun createResolvedUpstreamDataSourceFactory()', start)
s = s[:start] + '''        val originalHush = ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            resolvePlaybackDataSpec(dataSpec = dataSpec)
        }
        return DataSource.Factory {
            app.hush.music.polaris.PolarisTraceDataSource(originalHush.createDataSource(), "hush-original")
        }
    }

''' + s[end:]
s = once(s, '''    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            DefaultExtractorsFactory(),
        ).setLoadErrorHandlingPolicy(polarisRadioLoadErrorPolicy)''', '''    private fun createMediaSourceFactory(): androidx.media3.exoplayer.source.MediaSource.Factory {
        val music = DefaultMediaSourceFactory(createDataSourceFactory(), DefaultExtractorsFactory())
        val radioHttp = DefaultDataSource.Factory(
            this,
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("Polaris-Unbound/0.58")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000),
        )
        val radioBytes = DataSource.Factory {
            app.hush.music.polaris.PolarisTraceDataSource(radioHttp.createDataSource(), "radio-direct")
        }
        val radio = DefaultMediaSourceFactory(radioBytes, DefaultExtractorsFactory())
            .setLoadErrorHandlingPolicy(app.hush.music.polaris.PolarisRadioLoadErrorPolicy().apply { radioActive = true })
        return app.hush.music.polaris.PolarisSourceFactory(music, radio)
    }''', 'per MediaSource routing including HLS children')
s = once(s, '''        app.hush.music.polaris.PolarisRadioNetwork.radioActive =
            mediaItem?.mediaId?.startsWith("polaris:station:") == true
        // Hush's YouTube automix, prefetch and queue hydration do not apply to live radio.
        if (app.hush.music.polaris.PolarisRadioNetwork.radioActive) {''', '''        // Hush's YouTube automix, prefetch and queue hydration do not apply to live radio.
        app.hush.music.polaris.PolarisErrorReport.record("item-transition " + app.hush.music.polaris.PolarisErrorReport.item(mediaItem))
        if (mediaItem?.mediaId?.startsWith("polaris:station:") == true) {''', 'transition no global routing state')
s = re.sub(r'^\s*app\.hush\.music\.polaris\.PolarisRadioNetwork\.radioActive = true\n', '\n', s, flags=re.M)

# A missing Hush cache key must not let an opaque video id fall through to file IO.
s = once(s, '        val mediaId = dataSpec.key ?: return dataSpec\n', '''        val mediaId = dataSpec.key ?: dataSpec.uri.toString().takeIf {
            Regex("[A-Za-z0-9_-]{11}").matches(it)
        } ?: throw java.io.IOException("Unresolved media request without a valid track cache key")
        val keyedSpec = if (dataSpec.key == null) dataSpec.buildUpon().setKey(mediaId).build() else dataSpec
        if (keyedSpec !== dataSpec) return resolvePlaybackDataSpec(keyedSpec)
''', 'recover valid opaque song IDs before resolving')
service.write_text(s)

callback = base / 'playback/MediaLibrarySessionCallback.kt'
c = callback.read_text()
helpers = '''        private fun <T> polarisFuture(
            stage: String,
            block: suspend CoroutineScope.() -> T,
        ): ListenableFuture<T> = scope.future(Dispatchers.IO) {
            app.hush.music.polaris.PolarisErrorReport.record("request " + stage)
            try {
                block()
            } catch (cancelled: CancellationException) {
                app.hush.music.polaris.PolarisErrorReport.record("cancelled " + stage)
                throw cancelled
            } catch (error: Exception) {
                if (error !is NoPlaybackChange) {
                    app.hush.music.polaris.PolarisErrorReport.failure(stage, error)
                }
                throw error
            }
        }

        private suspend fun polarisPlaybackItem(item: MediaItem): MediaItem {
            val id = item.mediaId
            if (id.startsWith("polaris:station:")) {
                if (item.localConfiguration != null) return item
                val station = id.removePrefix("polaris:station:")
                return PolarisRadioPlayback.mediaItem(station, PolarisRadioPlayback.resolveUrl(station))
            }
            if (item.localConfiguration != null) return item
            onlineSearchItemCache[id]?.takeIf { it.localConfiguration != null }?.let { return it }
            database.song(id).first()?.let { return it.toMediaItem() }
            if (Regex("[A-Za-z0-9_-]{11}").matches(id)) {
                val artist = item.mediaMetadata.artist?.toString().orEmpty()
                val metadata = app.hush.music.models.MediaMetadata(
                    id = id,
                    title = item.mediaMetadata.title?.toString() ?: id,
                    artists = if (artist.isBlank()) emptyList() else listOf(app.hush.music.models.MediaMetadata.Artist(null, artist)),
                    duration = ((item.mediaMetadata.durationMs ?: -1000L) / 1000L).toInt(),
                )
                return metadata.toMediaItem()
            }
            throw java.io.IOException("Cannot play unresolved browse item id=" + id)
        }

        private fun polarisSetFuture(
            block: suspend CoroutineScope.() -> MediaSession.MediaItemsWithStartPosition,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = polarisFuture("set-media-items") {
            val result = block()
            val items = result.mediaItems.map { polarisPlaybackItem(it) }
            items.forEach { app.hush.music.polaris.PolarisErrorReport.record("set-ready " + app.hush.music.polaris.PolarisErrorReport.item(it)) }
            MediaSession.MediaItemsWithStartPosition(items, result.startIndex, result.startPositionMs)
        }

'''
c = once(c, '        private fun browsableExtras(\n', helpers + '        private fun browsableExtras(\n', 'instrument and normalize controller items')
for name,stage in [('onGetChildren','children'), ('onGetItem','item'), ('onGetSearchResult','search-result'), ('onSetMediaItems','set'), ('onAddMediaItems','add')]:
    start = c.index('        override fun ' + name + '(')
    # First IO future belongs to this callback, independent of the following callback.
    pos = c.index('            scope.future(Dispatchers.IO) {', start)
    assert pos < c.find('\n        override fun ', start + 1) if c.find('\n        override fun ', start + 1) != -1 else True
    new = '            polarisSetFuture future@ {' if name == 'onSetMediaItems' else '            polarisFuture("' + stage + '") future@ {'
    c = c[:pos] + c[pos:].replace('            scope.future(Dispatchers.IO) {', new, 1)

start = c.index('        override fun onGetChildren(')
end = c.index('        override fun onGetItem(', start)
browse = c[start:end]
browse = once(browse, '                val items =\n', '''                app.hush.music.polaris.PolarisErrorReport.record(
                    "browse parent=" + parentId + " page=" + page + " pageSize=" + pageSize + " controller=" + browser.packageName
                )
                val items =
''', 'persist exact browse request')
browse = once(browse, '                LibraryResult.ofItemList(items.paged(page, pageSize), params)', '''                val returnedItems = items.paged(page, pageSize)
                app.hush.music.polaris.PolarisErrorReport.record("browse-result parent=" + parentId + " total=" + items.size + " returned=" + returnedItems.size)
                LibraryResult.ofItemList(returnedItems, params)''', 'record actual post-pagination response')
c = c[:start] + browse + c[end:]
c = once(c, '''                        if (query.isBlank()) {
                            listOf(item)
                        } else {''', '''                        if (query.isBlank()) {
                            listOf(polarisPlaybackItem(item))
                        } else {''', 'normalize addMediaItems as well as setMediaItems')
c = once(c, '                                resolved.ifEmpty { listOf(item) }', '                                resolved.ifEmpty { listOf(polarisPlaybackItem(item)) }', 'never enqueue unresolved failed music search')
c = once(c, '                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult', '''                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                app.hush.music.polaris.PolarisErrorReport.record("set-request controller=" + controller.packageName + " " + app.hush.music.polaris.PolarisErrorReport.item(firstItem))''', 'record selected item before resolution can fail')
callback.write_text(c)

# Retain old radio diagnostics while exposing the richer common report on both phone paths.
screen = polaris / 'PolarisRadioScreen.kt'
r = screen.read_text()
r = re.sub(r'^\s*PolarisRadioNetwork\.radioActive = true\n', '\n', r, flags=re.M)
r = once(r, '                    Text("앱 버전 코드: $versionCode")', '''                    Text("앱 버전 코드: $versionCode")
                    TextButton(onClick = { PolarisErrorReport.open(context) }) {
                        Text("오류 리포트 보기·공유")
                    }''', 'radio report entry')
screen.write_text(r)
projection = polaris / 'PolarisProjectionRecovery.kt'
p = projection.read_text()
p = re.sub(r'^\s*PolarisRadioNetwork\.radioActive = true\n', '\n', p, flags=re.M)
p = once(p, '    private fun onProjectionState(type: Int) {', '    private fun onProjectionState(type: Int) {\n        PolarisErrorReport.connection(type)', 'persist AA state')
projection.write_text(p)
polaris.joinpath('PolarisRadioDataSource.kt').unlink()

settings = base / 'ui/screens/settings/SettingsScreen.kt'
st = settings.read_text()
st = once(st, '            if (hasUpdate && !isUpdateDismissed) {', '''            item(key = "polaris_error_report") {
                androidx.compose.material3.TextButton(onClick = {
                    app.hush.music.polaris.PolarisErrorReport.open(context)
                }) { Text("Polaris 오류 리포트 보기·공유") }
            }
            if (hasUpdate && !isUpdateDismissed) {''', 'streaming settings report entry')
settings.write_text(st)
manifest = root / 'app/src/main/AndroidManifest.xml'
m = manifest.read_text()
m = once(m, '        <service\n            android:name=".playback.MusicService"', '''        <activity android:name="app.hush.music.polaris.PolarisReportActivity" android:exported="false" />
        <service
            android:name=".playback.MusicService"''', 'private phone report activity')
manifest.write_text(m)

# Preserve the existing station endpoints. Parse JSON/JSONP structure instead of first-web-URL fallback.
resolver = polaris / 'PolarisRadioResolver.kt'
old = resolver.read_text()
start = old.index('    fun extractStreamUrl(raw: String): String? {')
old = old[:start] + '''    fun extractStreamUrl(raw: String): String? {
        val normalized = raw.replace("\\\\/", "/").replace("\\\\u0026", "&").replace("&amp;", "&")
        val candidates = mutableListOf<Pair<String, Int>>()
        fun visit(value: Any?, path: String) {
            when (value) {
                is org.json.JSONObject -> value.keys().forEach { key -> visit(value.opt(key), path + "." + key) }
                is org.json.JSONArray -> (0 until value.length()).forEach { visit(value.opt(it), path) }
                is String -> {
                    val score = PolarisSourcePolicy.audioUrlScore(value, path)
                    if (score >= 0) candidates += value to score
                }
            }
        }
        // Broadcasters may return JSONP (callback({...})); no code is executed.
        val objectStart = normalized.indexOf('{')
        val objectEnd = normalized.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            runCatching { visit(org.json.JSONObject(normalized.substring(objectStart, objectEnd + 1)), "") }
        }
        // Plain-text endpoints are accepted only for recognizable audio URLs.
        Regex("https?://[^\\\"'\\\\s,}\\\\)]+", RegexOption.IGNORE_CASE).findAll(normalized).forEach { match ->
            val score = PolarisSourcePolicy.audioUrlScore(match.value)
            if (score >= 0) candidates += match.value to score
        }
        return candidates.maxByOrNull { it.second }?.first
    }
}
'''
old = once(old, '            val raw = connection.inputStream.bufferedReader().use { it.readText() }', '''            val status = connection.responseCode
            PolarisErrorReport.record("radio-resolve station=" + id + " HTTP=" + status)
            if (status !in 200..299) throw java.io.IOException("Radio resolver HTTP " + status + " station=" + id)
            val raw = connection.inputStream.bufferedReader().use { it.readText() }''', 'resolver response diagnostics')
resolver.write_text(old)

gradle = root / 'app/build.gradle.kts'
g = gradle.read_text()
g = once(g, 'versionCode = 100565', 'versionCode = 100566', 'versionCode')
g = once(g, 'versionName = "50.3"', 'versionName = "50.4"', 'versionName')
g = once(g, '    testImplementation(libs.junit)', '    testImplementation(libs.junit)\n    testImplementation("org.json:json:20240303")', 'real JSON parser for local resolver regression tests')
gradle.write_text(g)
print('PASS 50.4: per-source radio/Hush routing, normalized AA items, bounded persistent report and TXT share')
