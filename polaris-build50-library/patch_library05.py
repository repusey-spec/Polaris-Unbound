#!/usr/bin/env python3
"""50.5: repaired radio restore, complete queues, native AA search, small icon."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
base = root / 'app/src/main/kotlin/app/hush/music'
overlay = Path(__file__).resolve().parent / 'fixes05'
polaris = base / 'polaris'

def once(s, a, b, label):
    assert s.count(a) == 1, f'{label}: {s.count(a)} anchors'
    return s.replace(a, b, 1)

for src in overlay.glob('*.kt'):
    target = (root / 'app/src/test/kotlin/app/hush/music/polaris' if src.name.endswith('Test.kt') else polaris) / src.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, target)

# Repair every conversion of persisted metadata, including the service's direct
# restore path which bypasses MediaSession callbacks altogether.
ext = base / 'extensions/MediaItemExt.kt'
s = ext.read_text()
s = once(s, 'fun MediaMetadata.toMediaItem() =\n    MediaItem', '''fun MediaMetadata.toMediaItem(): MediaItem =
    if (id.startsWith("polaris:station:")) {
        app.hush.music.polaris.PolarisRadioRequests.queueItem(id.removePrefix("polaris:station:"))
            .buildUpon().setTag(this).build()
    } else MediaItem''', 'persistent radio conversion')
ext.write_text(s)

callback = base / 'playback/MediaLibrarySessionCallback.kt'
c = callback.read_text()
c = once(c, '''                if (item.localConfiguration != null) return item
                val station = id.removePrefix("polaris:station:")
                return PolarisRadioPlayback.mediaItem(station, PolarisRadioPlayback.resolveUrl(station))''', '''                return app.hush.music.polaris.PolarisRadioRequests.normalize(item)''', 'validate rather than trust localConfiguration')
c = once(c, '''            val items = result.mediaItems.map { polarisPlaybackItem(it) }
            items.forEach''', '''            var items = result.mediaItems.map { polarisPlaybackItem(it) }
            var index = result.startIndex
            var position = result.startPositionMs
            val selected = items.getOrNull(index.coerceAtLeast(0))
            if (selected != null && app.hush.music.polaris.PolarisRadioRequests.stationId(selected) != null) {
                val radio = app.hush.music.polaris.PolarisRadioPlaylist.selection(context, selected)
                items = radio.items
                index = radio.index
                position = 0L
                app.hush.music.polaris.PolarisErrorReport.record("radio-set queue=" + items.size + " selected=" + index)
            }
            items.forEach''', 'AA set full queue before Media3 assigns it')
c = once(c, '            MediaSession.MediaItemsWithStartPosition(items, result.startIndex, result.startPositionMs)',
         '            MediaSession.MediaItemsWithStartPosition(items, index, position)', 'AA selection index')
start = c.index('            // A Media3 resumption request must not reopen persisted live radio')
end = c.index('            return MediaSession.MediaItemsWithStartPosition(\n                result.items,', start)
c = c[:start] + '''            val selected = result.items.getOrNull(result.startIndex.coerceAtLeast(0))
            if (selected != null && app.hush.music.polaris.PolarisRadioRequests.stationId(selected) != null) {
                // Rebuild even for non-playing resumption: no expired token or
                // broken polaris: URI survives to a subsequent Play command.
                val id = requireNotNull(app.hush.music.polaris.PolarisRadioRequests.stationId(selected))
                val radio = app.hush.music.polaris.PolarisRadioPlaylist.selection(
                    context, app.hush.music.polaris.PolarisRadioRequests.queueItem(id)
                )
                app.hush.music.polaris.PolarisErrorReport.record("radio-resume queue=" + radio.items.size)
                return MediaSession.MediaItemsWithStartPosition(radio.items, radio.index, 0L)
            }
''' + c[end:]

# MediaBrowser apps cannot open the host's search UI through an assistant Intent.
# Keep the existing host search callbacks and YouTube.search engine. Remove the
# broken duplicate entry; advertise native in-app search explicitly at the root.
start = c.index('                        "polaris:voice_search" -> {', c.index('override fun onGetChildren('))
end = c.index('                        MusicService.ROOT -> {', start)
c = c[:start] + c[end:]
c = once(c, '(listOf(polarisVoiceSearchItem()) + hushItems).map { item ->', 'hushItems.map { item ->', 'remove external assistant menu')
start = c.index('        /**\n         * AA owns the on-screen mic.')
end = c.index('        private fun browsableMediaItem(', start)
c = c[:start] + c[end:]
root_start = c.index('        override fun onGetLibraryRoot(')
root_end = c.index('        override fun onSearch(', root_start)
r = c[root_start:root_end]
r = once(r, '''                    params,
                ),''', '''                    MediaLibraryService.LibraryParams.Builder()
                        .setExtras(Bundle(params?.extras ?: Bundle()).apply {
                            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                        }).build(),
                ),''', 'native AA search capability')
c = c[:root_start] + r + c[root_end:]
c = once(c, '            Futures.immediateFuture(LibraryResult.ofVoid(params)).also {',
    '            Futures.immediateFuture(LibraryResult.ofVoid(params)).also {\n                app.hush.music.polaris.PolarisErrorReport.record("search engine=Hush-YouTubeMusic stage=request")', 'report native engine without query text')
# The browse helper is not registered anywhere now, so remove its orphan lookup.
start = c.index('                    mediaId == "polaris:voice_search" -> {')
end = c.index('\n                    ', c.index('\n                    }', start) + len('\n                    }'))
c = c[:start] + c[end:]
start = c.index('        private fun polarisVoiceSearchItem(): MediaItem =')
end = c.index('        private fun browsableMediaItem(', start)
c = c[:start] + c[end:]
c = once(c, '''                .search(query, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()''', '''                .search(query, YouTube.SearchFilter.FILTER_SONG)
                .onFailure { app.hush.music.polaris.PolarisErrorReport.failure("search-youtube", it) }
                .getOrNull()''', 'report native music search failures instead of silently empty results')
callback.write_text(c)

service = base / 'playback/MusicService.kt'
s = service.read_text()
s = once(s, '''        val radioBytes = DataSource.Factory {
            app.hush.music.polaris.PolarisTraceDataSource(radioHttp.createDataSource(), "radio-direct")
        }''', '''        val resolvedRadio = ResolvingDataSource.Factory(radioHttp) { spec ->
            app.hush.music.polaris.PolarisRadioRequests.resolve(spec)
        }
        val radioBytes = DataSource.Factory {
            app.hush.music.polaris.PolarisTraceDataSource(resolvedRadio.createDataSource(), "radio-direct")
        }''', 'resolve internal radio requests before HTTP')
s = once(s, '''        val itemQueue = persistedQueue.toQueue()''', '''        val restoredRadio = persistedQueue.items.getOrNull(persistedQueue.mediaItemIndex.coerceAtLeast(0))
            ?.takeIf { it.id.startsWith("polaris:station:") }
        if (restoredRadio != null) {
            withContext(Dispatchers.Main.immediate) {
                currentQueue = EmptyQueue
                queueTitle = "Radio"
                app.hush.music.polaris.PolarisRadioPlaylist.set(this@MusicService.player,
                    this@MusicService, restoredRadio.toMediaItem())
                originalQueueSize = player.mediaItemCount
                player.playWhenReady = false
                currentMediaMetadata.value = player.currentMetadata
                isHydratingRestoredQueue = false
                player.prepare()
                app.hush.music.polaris.PolarisErrorReport.record("radio-persistent-restore queue=" + player.mediaItemCount)
            }
            return
        }
        val itemQueue = persistedQueue.toQueue()''', 'service restore complete radio queue bypass Hush hydration')
s = once(s, '        if (mediaId.isBlank() || mediaId.isLocalMediaId() || index < 0) return',
         '        if (mediaId.isBlank() || mediaId.isLocalMediaId() || mediaId.startsWith("polaris:station:") || index < 0) return', 'never rewrite radio URI on music source toggle')
# Guard common music-only URL warming entry points, including restore callers.
for name in ['startPlaybackUrlPrefetch', 'warmPlaybackUrl']:
    start = s.index(' fun ' + name + '(')
    pos = s.index('        if (mediaId.isBlank() || mediaId.isLocalMediaId()) return', start)
    s = s[:pos] + s[pos:].replace('        if (mediaId.isBlank() || mediaId.isLocalMediaId()) return',
        '        if (mediaId.isBlank() || mediaId.isLocalMediaId() || mediaId.startsWith("polaris:station:")) return', 1)
s = once(s, '                player.setMediaItem(app.hush.music.polaris.PolarisRadioPlayback.mediaItem(next, url))',
    '                app.hush.music.polaris.PolarisRadioPlaylist.set(player, this@MusicService, app.hush.music.polaris.PolarisRadioPlayback.mediaItem(next, url))', 'hardware skip keeps all stations')
s = once(s, '                player.setMediaItem(radioItem)',
    '                app.hush.music.polaris.PolarisRadioPlaylist.set(player, this@MusicService, radioItem)', 'direct voice radio keeps all stations')
s = once(s, '                player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))', '''                if (player.getMediaItemAt(index).mediaId.startsWith("polaris:station:")) {
                    player.seekToDefaultPosition(index)
                } else {
                    player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))
                }''', 'restored radio seeks to live default instead of old position')
s = once(s, '''        if (mediaItem?.mediaId?.startsWith("polaris:station:") == true) {
            currentMediaMetadata.value = player.currentMetadata''', '''        if (mediaItem?.mediaId?.startsWith("polaris:station:") == true) {
            currentQueue = EmptyQueue
            queueTitle = "Radio"
            originalQueueSize = player.mediaItemCount
            currentMediaMetadata.value = player.currentMetadata''', 'radio never retains streaming continuation queue')
service.write_text(s)

factory = polaris / 'PolarisSourceFactory.kt'
s = factory.read_text()
s = once(s, '        val selected = if (PolarisSourcePolicy.isRadio(mediaItem.mediaId)) radio else music',
    '''        val radioItem = PolarisSourcePolicy.isRadio(mediaItem.mediaId)
        val playable = if (radioItem) PolarisRadioRequests.normalize(mediaItem) else mediaItem
        val selected = if (radioItem) radio else music''', 'defense at final MediaSource boundary')
s = once(s, 'selected.createMediaSource(mediaItem)', 'selected.createMediaSource(playable)', 'do not forward malformed original URI')
factory.write_text(s)

screen = polaris / 'PolarisRadioScreen.kt'
s = screen.read_text().replace('                        player.setMediaItem(item)', '                        PolarisRadioPlaylist.set(player, context, item)')
assert 'PolarisRadioPlaylist.set(player, context, item)' in s
screen.write_text(s)

recovery = polaris / 'PolarisRadioRecovery.kt'
s = recovery.read_text()
s = once(s, '                player.setMediaItem(freshItem)',
    '''                val refreshedIndex = player.currentMediaItemIndex
                player.replaceMediaItem(refreshedIndex, freshItem)
                player.seekToDefaultPosition(refreshedIndex)''', 'repair current source at live edge without deleting queue')
recovery.write_text(s)
projection = polaris / 'PolarisProjectionRecovery.kt'
s = projection.read_text()
s = once(s, '                player.setMediaItem(PolarisRadioPlayback.mediaItem(id, liveUrl))',
    '                PolarisRadioPlaylist.set(player, context, PolarisRadioPlayback.mediaItem(id, liveUrl))', 'restore complete radio queue on reconnect')
projection.write_text(s)

policy = polaris / 'PolarisRadioLoadErrorPolicy.kt'
s = policy.read_text()
s = once(s, '        val error = info.exception', '''        val error = info.exception
        var cause: Throwable? = error
        repeat(12) {
            if (cause is java.net.MalformedURLException) return C.TIME_UNSET
            cause = cause?.cause
        }''', 'malformed addresses do not enter fast retry loop')
policy.write_text(s)

# Preserve old evidence but prevent a 50.4 last-error from being mistaken for a
# fresh 50.5 failure after installing the update.
report = polaris / 'PolarisErrorReport.kt'
s = report.read_text()
s = once(s, 'appendLine("ERROR stage=$stage ${PolarisErrorReport.item(item)}")',
    'appendLine("ERROR build=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} at=${Date()} stage=$stage ${PolarisErrorReport.item(item)}")', 'error build and timestamp')
s = once(s, '.putString("last-error", safe)?.apply()',
    '.putString("last-error", safe)?.putInt("last-error-build", BuildConfig.VERSION_CODE)?.apply()', 'remember producing build')
s = once(s, '            appendLine(prefs.getString("last-error", "아직 기록된 오류가 없습니다."))', '''            val lastError = prefs.getString("last-error", null)
            if (lastError != null && prefs.getInt("last-error-build", -1) != BuildConfig.VERSION_CODE) {
                appendLine("[이전 빌드에서 남은 오류 기록입니다. 현재 빌드의 새 오류가 아닙니다.]")
            }
            appendLine(lastError ?: "아직 기록된 오류가 없습니다.")''', 'label errors retained from old APK')
report.write_text(s)

# A 24dp vector has transparent negative space at all densities. Never reuse a
# 108dp adaptive foreground bitmap with gravity=center as a notification icon.
res = root / 'app/src/main/res'
for name in ['polaris_small_icon', 'small_icon', 'app_icon_small', 'app_icon_monochrome']:
    shutil.copyfile(overlay / 'polaris_small_icon.xml', res / 'drawable' / (name + '.xml'))
for path in res.glob('mipmap-anydpi*/ic_launcher*.xml'):
    s = path.read_text()
    s = s.replace('<monochrome android:drawable="@mipmap/ic_launcher_monochrome"', '<monochrome android:drawable="@drawable/polaris_small_icon"')
    path.write_text(s)
shim = root / 'waze-shim/build.gradle.kts'
s = shim.read_text()
s = once(s, '        include("values/ic_launcher_background.xml")',
    '        include("values/ic_launcher_background.xml")\n        include("drawable/polaris_small_icon.xml")', 'include referenced monochrome vector in generated shim icon resources')
shim.write_text(s)

gradle = root / 'app/build.gradle.kts'
g = gradle.read_text()
g = once(g, 'versionCode = 100566', 'versionCode = 100567', 'versionCode')
g = once(g, 'versionName = "50.4"', 'versionName = "50.5"', 'versionName')
g = once(g, '    testImplementation(libs.junit)', '    testImplementation(libs.junit)\n    testImplementation("org.robolectric:robolectric:4.14.1")', 'real Android MediaItem restore regression tests')
gradle.write_text(g)
print('PASS 50.5 patch: safe restore/loader boundary; complete radio queues; native AA search; vector small icons')
