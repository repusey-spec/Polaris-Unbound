from pathlib import Path
import sys,subprocess,hashlib
p=Path(sys.argv[1]);s=p/'app/src/main/java/com/dd3boh/outertune'
assert 'versionCode = 100601' in (p/'app/build.gradle.kts').read_text()
assert 'versionName = "6.0.1"' in (p/'app/build.gradle.kts').read_text()
for f in ['playback/SongResolver.kt','playback/StreamAddresses.kt','playback/BadCachedCopy.kt']:
 path=s/f
 if path.exists():
  rel=str(path.relative_to(p)); original=subprocess.check_output(['git','-C',str(p),'show','HEAD:'+rel]);assert original==path.read_bytes(),rel+' native engine unexpectedly edited'
assert 'polaris6-song.db' in (s/'db/MusicDatabase.kt').read_text()
assert 'polaris6-settings' in (s/'utils/DataStore.kt').read_text()
assert 'polaris6-download' in (s/'di/AppModule.kt').read_text()
assert 'PolarisMediaSources' in (s/'playback/MusicService.kt').read_text()
assert 'PolarisVoiceRouter' in (s/'playback/MediaLibrarySessionCallback.kt').read_text()
assert 'AUDIO-UNDERRUN' in (s/'polaris/PolarisRadioSession.kt').read_text()
assert 'StreamCheck.rescueDeniedWebHead(status, past, pot != null)' in (s/'utils/YTPlayerUtils.kt').read_text()
assert 'fun rescueDeniedWebHead' in (s/'utils/StreamCheck.kt').read_text()
print('PASS: 6.0.1 identity, tokenless 206 guard, original resolver, radio routing and diagnostics')
