from pathlib import Path
import hashlib,subprocess,sys
p=Path(sys.argv[1]).resolve()
s=p/'app/src/main/java/com/dd3boh/outertune'
g=(p/'app/build.gradle.kts').read_text()
assert 'versionCode = 100604' in g and 'versionName = "6.04"' in g
assert 'applicationId = "app.hush.music.polarisradio.debug"' in g
assert 'implementation(libs.media3.hls)' in g
for rel in ['utils/StreamCheck.kt','utils/StreamOrder.kt','utils/StreamFamily.kt','utils/potoken/PoTokenGenerator.kt','playback/StandIns.kt','playback/BadCachedCopy.kt']:
 f=s/rel
 original=subprocess.check_output(['git','-C',str(p),'show','HEAD:'+str(f.relative_to(p))])
 assert f.read_bytes()==original,rel
 print('UNCHANGED upstream',rel,hashlib.sha256(original).hexdigest()[:16])
assert 'intertune603-song.db' in (s/'db/MusicDatabase.kt').read_text()
assert 'intertune603-settings' in (s/'utils/DataStore.kt').read_text()
for value in ['intertune603-exoplayer','intertune603-download']:
 assert value in (s/'di/AppModule.kt').read_text()
for rel,required in {
 'polaris/PolarisMediaSources.kt':['HlsMediaSource.Factory(radioDataSources)','else songs.createMediaSource(mediaItem)'],
 'utils/YTPlayerUtils.kt':['WebStreamProbe.read(streamCalls','webStreamRejections.isRejected','webStreamRejections.selected'],
 'playback/MusicService.kt':['noteWebStreamRejected(streamAddresses.addressOf(failedId))','noteWebStreamRejected(streamAddresses.addressOf(id))'],
 'polaris/PolarisRadioMetadataRefresh.kt':['liveItem.buildUpon()', '.setMediaMetadata(metadata)'],
}.items():
 for value in required: assert value in (s/rel).read_text(),(rel,value)
assert 'rescueDeniedWebHead' not in (s/'utils/YTPlayerUtils.kt').read_text()
assert '-keep class androidx.tracing.**' in (p/'app/proguard-rules.pro').read_text()
print('PASS: 604 fixes; 603 data namespace; original routing, token generation and caches preserved')
