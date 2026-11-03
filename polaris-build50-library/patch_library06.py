#!/usr/bin/env python3
"""50.6: template UI navigation over the verified 50.5 audio session."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
base = root / 'app/src/main/kotlin/app/hush/music'
here = Path(__file__).resolve().parent

def once(s, old, new, label):
    assert s.count(old) == 1, f'{label}: {s.count(old)} anchors'
    return s.replace(old, new, 1)

for src in (here / 'car06').glob('*.kt'):
    target = (root / 'app/src/test/kotlin/app/hush/music/polaris' if src.name.endswith('Test.kt') else base / 'polaris') / src.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, target)

callback = base / 'playback/MediaLibrarySessionCallback.kt'
s = callback.read_text()
s = once(s, '                    .add(MediaSessionConstants.CommandToggleLike)', '''                    .apply {
                        if (controller.packageName == context.packageName) {
                            add(SessionCommand(app.hush.music.polaris.PolarisCarNavigation.TOKEN_COMMAND, Bundle.EMPTY))
                        }
                    }
                    .add(MediaSessionConstants.CommandToggleLike)''', 'allow self controller token request')
s = once(s, '''            when (customCommand.customAction) {''', '''            if (customCommand.customAction == app.hush.music.polaris.PolarisCarNavigation.TOKEN_COMMAND) {
                if (controller.packageName != context.packageName) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS,
                    Bundle().apply {
                        putParcelable(app.hush.music.polaris.PolarisCarNavigation.TOKEN_KEY, session.platformToken)
                    }))
            }
            when (customCommand.customAction) {''', 'supply existing platform session to CAL media manager')
callback.write_text(s)

# Collect useful next-song evidence without guessing the cause of the user's
# code 2000 error or changing the proven source engines/retry policy.
service = base / 'playback/MusicService.kt'
s = service.read_text()
assert s.count('scopeJob = Job()') == 2, 'shared service job anchors changed'
s = s.replace('scopeJob = Job()', 'scopeJob = app.hush.music.polaris.PolarisPlaybackJobs.createServiceJob()')
s = once(s, '''                try {
                    playbackUrlPrefetchSemaphore.withPermit {
                        resolveAndCachePlaybackUrl(mediaId)
                    }
                } catch (_: CancellationException) {
                    null
                } finally {
                    playbackUrlPrefetchInFlight.remove(mediaId, deferred)
                }''', '''                try {
                    app.hush.music.polaris.PolarisPlaybackJobs.prefetch(mediaId, { failedId, failure ->
                        app.hush.music.polaris.PolarisErrorReport.failure("prefetch id=" + failedId, failure)
                    }) {
                        playbackUrlPrefetchSemaphore.withPermit { resolveAndCachePlaybackUrl(mediaId) }
                    }
                } finally {
                    playbackUrlPrefetchInFlight.remove(mediaId, deferred)
                }''', 'isolate expected prefetch failures and preserve cancellation')
s = once(s, '        super.onMediaItemTransition(mediaItem, reason)', '''        super.onMediaItemTransition(mediaItem, reason)
        app.hush.music.polaris.PolarisErrorReport.record("transition reason=" + reason +
            " index=" + player.currentMediaItemIndex + " queue=" + player.mediaItemCount +
            " ready=" + player.playWhenReady + " " + app.hush.music.polaris.PolarisErrorReport.item(mediaItem))''', 'persist auto transition reason and queue position')
s = once(s, '        app.hush.music.polaris.PolarisErrorReport.failure("player", error, player.currentMediaItem)', '''        app.hush.music.polaris.PolarisErrorReport.record("player-failure index=" + player.currentMediaItemIndex +
            " queue=" + player.mediaItemCount + " state=" + player.playbackState + " position=" + player.currentPosition)
        val reportIndex = player.currentMediaItemIndex
        for (nearby in (reportIndex - 1)..(reportIndex + 1)) {
            if (nearby in 0 until player.mediaItemCount) {
                app.hush.music.polaris.PolarisErrorReport.record("queue-near-error index=" + nearby + " " +
                    app.hush.music.polaris.PolarisErrorReport.item(player.getMediaItemAt(nearby)))
            }
        }
        app.hush.music.polaris.PolarisErrorReport.failure("player", error, player.currentMediaItem)''', 'persist failed next track and neighbors')
service.write_text(s)

trace = base / 'polaris/PolarisTraceDataSource.kt'
s = trace.read_text()
s = once(s, '"load-opened route=$route length=$length content-type=$type"',
    '"load-opened route=$route key=${dataSpec.key ?: "none"} scheme=${delegate.uri?.scheme ?: "none"} host=${delegate.uri?.host ?: "none"} length=$length content-type=$type"', 'record resolved byte route without full URL')
trace.write_text(s)

manifest = root / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text()
s = once(s, '    <uses-permission android:name="android.permission.INTERNET" />', '''    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="androidx.car.app.MEDIA_TEMPLATES" />''', 'CAL media permission')
s = once(s, '        <activity android:name="app.hush.music.polaris.PolarisReportActivity" android:exported="false" />', '''        <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="8" />
        <meta-data android:name="com.google.android.gms.car.notification.SmallIcon" android:resource="@drawable/polaris_small_icon" />
        <service android:name="app.hush.music.polaris.PolarisCarService"
            android:exported="true" android:label="Polaris" android:icon="@mipmap/ic_launcher">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.MEDIA" />
            </intent-filter>
        </service>
        <activity android:name="app.hush.music.polaris.PolarisReportActivity" android:exported="false" />''', 'car UI service with explicit minimum host API')
manifest.write_text(s)
desc = root / 'app/src/main/res/xml/automotive_app_desc.xml'
s = desc.read_text()
s = once(s, '    <uses name="media" />', '    <uses name="media" />\n    <uses name="template" />', 'host template discovery alongside existing media service')
desc.write_text(s)

gradle = root / 'app/build.gradle.kts'
s = gradle.read_text()
s = once(s, '    implementation("androidx.car.app:app:1.7.0")', '''    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    testImplementation("androidx.car.app:app-testing:1.7.0")''', 'pin same stable car library with UI tests')
s = once(s, '        unitTests.isReturnDefaultValues = true',
    '        unitTests.isReturnDefaultValues = true\n        unitTests.isIncludeAndroidResources = true', 'real resource-backed car template tests')
s = once(s, 'versionCode = 100567', 'versionCode = 100568', 'version code')
s = once(s, 'versionName = "50.5"', 'versionName = "50.6"', 'version name')
gradle.write_text(s)
print('PASS 50.6: CAL navigation, own-engine search, isolated track failures and next-track diagnostics')
