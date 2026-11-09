from pathlib import Path
import sys
r=Path(sys.argv[1]); b=r/'app/src/main/kotlin/app/hush/music'
s=(b/'playback/MusicService.kt').read_text(); eq=(b/'eq/audio/CustomEqualizerAudioProcessor.kt').read_text()
art=s[s.index('    private suspend fun hydrateNotificationArtwork'):s.index('    private fun isCurrentPlaybackItemLocal')]
checks={
 'PCM bypass and reusable allocation': 'outputBuffer = inputBuffer' in eq and 'workingBuffer = ByteBuffer.allocateDirect(size)' in eq and 'outputBuffer = ByteBuffer.allocateDirect(size)' not in eq,
 'EQ updates owned by audio thread': '@Volatile private var requestedSettings' in eq and 'applyRequestedSettings()' in eq,
 'artwork JPEG compression before Main commit': art.index('NotificationArtworkLoader.bitmapToArtworkData') < art.rindex('withContext(Dispatchers.Main.immediate)'),
 'artwork single flight cancellation and gate': 'notificationArtworkJob?.cancel()' in s and 'notificationArtworkGate.withPermit' in art and 'bitmap.recycle()' in art,
 'checkpoint bounded request and unchanged queue path': 'Channel.CONFLATED' in s and 'if (key.matches(lastQueueCheckpoint)) null' in s,
 'checkpoint clear and track identity guards': 'snapshot.first.generation != persistentSaveGeneration.get()' in s and 'playerState.currentMediaId == null' in s,
 'source logging no independent writer or full-file rewrite': all(x not in (b/'spotiflac/SpotiFLACDiag.kt').read_text() for x in ['Executors','readLines','writeText']),
 'source logging included in bounded journal report': 'PolarisDiagnosticJournal.dump("spotiflac")' in (b/'polaris/PolarisErrorReport.kt').read_text(),
 'new slow-path counters in sample and underrun': 'PolarisPlaybackWorkMetrics.summary()' in s and 'PolarisPlaybackWorkMetrics.summary()' in (b/'polaris/PolarisResourceDiagnostics.kt').read_text(),
 'correct update version': 'versionCode = 100574' in (r/'app/build.gradle.kts').read_text() and 'versionName = "50.12"' in (r/'app/build.gradle.kts').read_text(),
}
for label,ok in checks.items(): print(('PASS ' if ok else 'FAIL ')+label)
assert all(checks.values())
