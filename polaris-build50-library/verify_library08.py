from pathlib import Path
import sys, xml.etree.ElementTree as ET
r=Path(sys.argv[1]);b=r/'app/src/main/kotlin/app/hush/music'
d=(b/'playback/DownloadUtil.kt').read_text();v=(b/'polaris/PolarisDownloadValidation.kt').read_text();p=(b/'polaris/PolarisProbeService.kt').read_text()
s=(b/'playback/MusicService.kt').read_text();q=(b/'polaris/PolarisSerialDownloads.kt').read_text()
g=(r/'app/build.gradle.kts').read_text();manifest=(r/'app/src/main/AndroidManifest.xml').read_text();ET.fromstring(manifest)
checks={
 'single worker and safe failed-only mutations': 'child.cancelAndJoin()' in q and 'fun discardFailed' in q and 'old.state != State.FAILED' in q,
 'bounded sample reads in separate process': 'allocateDirect(256 * 1024)' in p and 'repeat(limit)' in p and 'postDelayed(watchdog' in p and ':polaris_verify' in manifest,
 'native watchdog does not kill player process': 'Process.killProcess(Process.myPid())' in p and ':polaris_verify' in (b/'App.kt').read_text(),
 'strict copy length check retained': 'written != expected' in (b/'polaris/PolarisDownloadIntegrity.kt').read_text(),
 'verification timeout retains partial for user retry or clear': 'error !is app.hush.music.polaris.PolarisVerificationTimeout' in d,
 'common resolver budget includes provider fallback and retries': 'resolveDeadline' in d and 'transfer = { downloadToFile(mediaId, title, startedAt, resolveDeadline)' in d and 'runInterruptible { resolveSpotiFLACDownloadUri' in d,
 'bounded network read factory does not alter playback': 'boundedFileDownloadFactory.createDataSource()' in d and '.readTimeout(15, TimeUnit.SECONDS)' in d,
 'failed records preserve saved music': 'fun discardFailedDownload' in d and 'fileDownloads.update { rows -> if (rows[id]?.state == Download.STATE_FAILED)' in d,
 'failure screen accessible and describes retry and safe clear': 'PolarisDownloadsActivity.open(context)' in (b/'ui/screens/settings/SettingsScreen.kt').read_text() and '실패 전체 재시도' in (b/'polaris/PolarisDownloadsActivity.kt').read_text(),
 'authentication final failures honor auto-skip preference': 'polarisFinalPlaybackFailure(error, "authentication")' in s and 'playback-decision=' in s and 'nextWindowIndex != player.currentMediaItemIndex' in s,
 'all app updater flavors disabled': '"UPDATER_AVAILABLE", "true"' not in g and 'if (!BuildConfig.UPDATER_AVAILABLE) return false' in (b/'utils/Updater.kt').read_text(),
 'inactive collection does not show progress toolbar': 'hasActiveDownloads(songs.map { it.song.id }, downloads)' in (b/'ui/screens/playlist/LocalPlaylistScreen.kt').read_text(),
 'shared playback resolver has an overall deadline': 'PolarisDownloadPolicy.resolve(deadline' in s and 'polarisScheduleRecoveryReset' in s,
 'legacy failed controls preserve completed audio': 'polarisDownloads.discardFailedDownload' in (b/'ui/screens/playlist/DownloadManagementScreen.kt').read_text(),
 '50.8 update identity and existing car UI intact': 'versionCode = 100570' in g and 'versionName = "50.8"' in g and (b/'polaris/PolarisCarScreens.kt').exists(),
}
for name,ok in checks.items():print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values())
