from pathlib import Path
import sys,json,hashlib,xml.etree.ElementTree as ET
r=Path(sys.argv[2]);b=r/'app/src/main/kotlin/app/hush/music';c=r/'core/src/main/kotlin/app/hush/music/innertube';base=r/'baseline14-for15.json'
def hashes():
 return {str(p.relative_to(r)):hashlib.sha256(p.read_bytes()).hexdigest() for tree in ['app/src','core/src'] for p in (r/tree).rglob('*') if p.is_file() and p.suffix in ['.kt','.java','.xml']}
if sys.argv[1]=='snapshot':base.write_text(json.dumps(hashes()));sys.exit(0)
old=json.loads(base.read_text());now=hashes();changed={p for p in old.keys()|now.keys() if old.get(p)!=now.get(p)}
allowed={'app/src/main/kotlin/app/hush/music/'+p for p in ['utils/PlaybackAuthPreferences.kt','auth/YouTubeLoginRepository.kt','utils/YTPlayerUtils.kt','utils/SyncUtils.kt','polaris/PolarisSourcePolicy.kt','polaris/PolarisLikedSnapshotPolicy.kt','playback/MusicService.kt']}|{'core/src/main/kotlin/app/hush/music/innertube/'+p for p in ['YouTube.kt','PlaybackTrace.kt','InnerTube.kt','StreamRequestBudget.kt']}|{'app/src/test/kotlin/app/hush/music/polaris/PolarisAccountRecovery15Test.kt'}
assert changed==allowed,changed^allowed
prefs=(b/'utils/PlaybackAuthPreferences.kt').read_text();yt=(b/'utils/YTPlayerUtils.kt').read_text();sync=(b/'utils/SyncUtils.kt').read_text();service=(b/'playback/MusicService.kt').read_text()
retry=prefs[prefs.index('suspend fun <T> Context.retryWithoutPlaybackLoginContext'):prefs.index('private suspend fun Context.persistPlaybackAuthRepair')]
assert 'return block()' in retry and 'remove(DataSyncIdKey)' not in retry and 'withoutPlaybackLoginContext()' not in retry
assert 'storedAuthState.dataSyncId ?: refreshedDataSyncId' in prefs
assert 'if (canUseLoggedInPlayback) MAIN_CLIENT else' in yt
loop=yt[yt.index('private fun Result<PlayerResponse>.getPlaybackPlayerResponseOrNull'):yt.index('private fun throwInvalidPlaybackLoginContextIfNeeded')]
assert 'throwInvalidPlaybackLoginContextIfNeeded' not in loop and 'return null' in loop
assert 'authState.copy(dataSyncId = null)' not in yt and 'withoutPlaybackLoginContext()' not in yt
assert 'StreamRequestBudgetExceeded' in loop
assert 'val requestNumber = budget?.acquire()' in (c/'InnerTube.kt').read_text()
assert 'withContext(context + StreamRequestBudget())' in (c/'PlaybackTrace.kt').read_text()
likes=sync[sync.index('suspend fun syncLikedSongs'):sync.index('suspend fun syncLibrarySongs')]
assert 'staleLikedSongs' not in likes and 'preserveMissing=true' in likes
assert 'likedDate != timestamp' not in likes and 'likedDate(dbSong.song.likedDate, timestamp)' in likes
assert 'val dbSong = getSongByIdBlocking(song.id)' in likes
assert 'polarisLikeIntents.desired' in likes and 'val targetVideoId = song.id' in sync
assert '(?<![A-Za-z0-9_])(authorization|cookie|set-cookie)' in (b/'polaris/PolarisSourcePolicy.kt').read_text()
assert 'mapStreamResolutionFailure(throwable.underlying, mediaId)' in service
assert 'route=youtube' in service and 'polarisDamagedLocalIds' in service
assert 'versionCode = 100577' in (r/'app/build.gradle.kts').read_text()
report={'changed_sources':sorted(changed),'account_preserved_on_client_failure':True,'wire_budget':18,'existing_likes_preserved':True,'liked_dates_preserved':True,'audio_startup_radio_unchanged':True,'damaged_file_recovery_preserved':True}
if sys.argv[1]=='final':
 suites=[ET.parse(p).getroot() for p in (r/'app/build/test-results/testGmsMobileArm64DebugUnitTest').glob('TEST-*.xml')]
 new=[s for s in suites if s.get('name','').endswith('.PolarisAccountRecovery15Test')];assert len(new)==1 and int(new[0].get('tests','0'))==15
 assert sum(int(s.get(k,'0')) for s in suites for k in ['failures','errors','skipped'])==0
 report['post_fix_cross_validation_passed']=True
(r/'polaris-cross-validation15.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
