from pathlib import Path
import sys,subprocess,hashlib
p=Path(sys.argv[1]);s=p/'app/src/main/java/com/dd3boh/outertune'
assert 'versionCode = 100600' in (p/'app/build.gradle.kts').read_text()
assert 'versionName = "6.0"' in (p/'app/build.gradle.kts').read_text()
for f in ['utils/YTPlayerUtils.kt','playback/SongResolver.kt','playback/StreamAddresses.kt','playback/BadCachedCopy.kt']:
 path=s/f
 if path.exists():
  rel=str(path.relative_to(p)); original=subprocess.check_output(['git','-C',str(p),'show','HEAD:'+rel]);assert original==path.read_bytes(),rel+' native engine unexpectedly edited'
assert 'polaris6-song.db' in (s/'db/MusicDatabase.kt').read_text()
assert 'polaris6-settings' in (s/'utils/DataStore.kt').read_text()
assert 'polaris6-download' in (s/'di/AppModule.kt').read_text()
assert 'PolarisMediaSources' in (s/'playback/MusicService.kt').read_text()
assert 'PolarisVoiceRouter' in (s/'playback/MediaLibrarySessionCallback.kt').read_text()
assert 'AUDIO-UNDERRUN' in (s/'polaris/PolarisRadioSession.kt').read_text()
print('PASS: identity, isolated data, native engine unchanged, radio routing and diagnostics')
