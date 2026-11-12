from pathlib import Path
import sys,json,hashlib,xml.etree.ElementTree as ET
r=Path(sys.argv[2]);b=r/'app/src/main/kotlin/app/hush/music';base=r/'baseline13-for14.json'
def hashes():
 return {str(p.relative_to(r)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (r/'app/src').rglob('*') if p.is_file() and p.suffix in ['.kt','.java','.xml']}
if sys.argv[1]=='snapshot':base.write_text(json.dumps(hashes()));sys.exit(0)
old=json.loads(base.read_text());now=hashes();changed={p for p in old.keys()|now.keys() if old.get(p)!=now.get(p)}
allowed={'app/src/main/kotlin/app/hush/music/'+p for p in ['playback/MusicService.kt','utils/SyncUtils.kt','polaris/PolarisLikeIntents.kt','polaris/PolarisOnlineRecovery.kt','polaris/PolarisLocalRoutes.kt']}|{'app/src/test/kotlin/app/hush/music/polaris/PolarisRecovery14Test.kt'}
assert changed==allowed,changed^allowed
s=(b/'playback/MusicService.kt').read_text();u=(b/'utils/SyncUtils.kt').read_text()
a=s.index('private fun resolvePlaybackDataSpec');q=s.index('if (mediaId in polarisDamagedLocalIds)',a);assert q<s.index('return dataSpec.withUri(downloaded.toUri())',a)
assert 'if (mediaId in polarisDamagedLocalIds) null else ownedLocalPlaybackFile' in s
assert 'mediaId in forceYouTubeOnceMediaIds || mediaId in polarisDamagedLocalIds' in s
recovery=s[s.index('if (!isLocalMedia && polarisLocalRoutes.wasFile(currentMediaId)'):s.index('// A track whose bytes came from our own SpotiFLAC')]
assert 'removeDownload' not in recovery and 'player.currentMediaItemIndex != resumeIndex' in recovery
assert 'polarisLikeIntents.desired(likeAccount, song.id, likeSnapshotStartedAt) == false' in u
assert 'polarisLikeRemoteMutex.withLock' in u and 'polarisLikeIntents.record' in u
like_call=u[u.index('private suspend fun likeVideoWithRetry'):u.index('fun likeSongs(')]
assert 'val targetVideoId = song.id' in like_call and 'getSetVideoId' not in like_call
assert 'polarisFlushPendingLikes(likeAccount, gen)' in u
assert 'notifyChildrenChanged(LIKED' in s
assert 'update(latest.copy(duration = duration))' in s and 'currentMediaMetadata.value?.id == mediaId' in s
assert 'versionCode = 100576' in (r/'app/build.gradle.kts').read_text()
report={'changed_sources':sorted(changed),'prior_startup_audio_io_fixes_preserved':True,'damaged_file_preserved_and_online_cache_isolated':True,'unlike_intent_persisted':True}
if sys.argv[1]=='final':
 suites=[ET.parse(p).getroot() for p in (r/'app/build/test-results/testGmsMobileArm64DebugUnitTest').glob('TEST-*.xml')]
 new=[s for s in suites if s.get('name','').endswith('.PolarisRecovery14Test')];assert len(new)==1 and int(new[0].get('tests','0'))==9
 assert sum(int(s.get(k,'0')) for s in suites for k in ['failures','errors','skipped'])==0
 report['post_fix_cross_validation_passed']=True
(r/'polaris-cross-validation14.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
