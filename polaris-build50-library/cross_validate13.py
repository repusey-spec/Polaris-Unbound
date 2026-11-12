#!/usr/bin/env python3
"""Independent 50.12/50.13 generated-source boundary and executed-test audit."""
from pathlib import Path
import hashlib, json, re, sys, zipfile, xml.etree.ElementTree as ET
root = Path(sys.argv[2]); snapshot = root/'polaris-baseline12.json'
service = 'app/src/main/kotlin/app/hush/music/playback/MusicService.kt'
updater = 'app/src/main/kotlin/app/hush/music/utils/UpdateNotificationManager.kt'
helper = 'app/src/main/kotlin/app/hush/music/polaris/PolarisLocalPrefetchGate.kt'
report_source = 'app/src/main/kotlin/app/hush/music/polaris/PolarisErrorReport.kt'
new_tests = {'PolarisLocalPrefetchGateTest': 8, 'PolarisDisabledUpdaterTest': 2}
allowed = {service, updater, 'app/build.gradle.kts', helper, report_source} | {
    'app/src/test/kotlin/app/hush/music/polaris/'+name+'.kt' for name in new_tests}
def hashes():
    files = list((root/'app/src').rglob('*')) + [root/'app/build.gradle.kts']
    return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files if p.is_file()}
def block(s, name):
    m = re.search(r'^    (?:(?:private|override|public|internal|protected) )?(?:suspend )?fun '+name+r'\(', s, re.M)
    assert m, name
    end = re.search(r'^    (?:(?:private|override|public|internal|protected) )?(?:suspend )?fun \w+\(', s[m.end():], re.M)
    assert end, name
    return s[m.start():m.end()+end.start()]
if sys.argv[1] == 'snapshot':
    s = (root/service).read_text()
    assert 'localPlaybackEvidence(mediaId' in block(s, 'startPlaybackUrlPrefetch').split('lateinit var deferred')[0]
    snapshot.write_text(json.dumps({'hashes':hashes(), 'service':s, 'updater':(root/updater).read_text(), 'report_source':(root/report_source).read_text()}))
    print('Recorded generated 50.12 source boundary'); sys.exit(0)
old = json.loads(snapshot.read_text()); current = hashes()
changed = {p for p in old['hashes'].keys() | current.keys() if old['hashes'].get(p) != current.get(p)}
# The existing pinned Hush copy task regenerates this packaged bridge archive during tests.
# Permit exactly that non-source build product only after compilation; keep every other
# asset/source byte under the original strict boundary comparison.
generated = 'app/src/mobile/assets/waze-shims.zip'
generated_changes = changed & {generated}
assert not generated_changes or sys.argv[1] == 'final', 'bridge archive changed before build'
assert changed - generated_changes == allowed, ('unexpected/missing changed paths', (changed - generated_changes) ^ allowed)
if generated_changes:
    producer = root/'waze-shim/build/outputs/apk/waze-shims.zip'
    assert producer.is_file() and hashlib.sha256(producer.read_bytes()).hexdigest() == current[generated], 'bridge asset does not match build output'
    with zipfile.ZipFile(root/generated) as archive:
        assert archive.namelist() and archive.testzip() is None, 'invalid generated bridge archive'

assert (root/report_source).read_text() == old['report_source'].replace('STREAM REQUEST DIAGNOSTICS (50.12)', 'STREAM REQUEST DIAGNOSTICS (${BuildConfig.VERSION_NAME})'), 'only diagnostic version text may change'
s = (root/service).read_text(); baseline = old['service']
# Strip only the three reviewed functions and one new field. Every other service byte
# (including stream resolution/fallback, queue, audio, radio and lifecycle) must match.
for name in ['publishPlaybackClientLabel', 'startPlaybackUrlPrefetch', 'warmPlaybackUrl']:
    baseline = baseline.replace(block(baseline, name), '<'+name+'>\n')
    s_mask = block(s, name)
    s = s.replace(s_mask, '<'+name+'>\n')
s = s.replace('    // Main-owned: discard a slow source-label lookup superseded by a newer publish.\n    private var playbackLabelRevision = 0L\n', '')
assert s == baseline, 'unreviewed service change, including resolver/fallback boundary'
s = (root/service).read_text()
prefetch = block(s, 'startPlaybackUrlPrefetch')
entry = prefetch.split('ioScope.async(')[0]
assert all(x not in entry for x in ['cachedPlaybackUrl(', 'localPlaybackEvidence(', 'fileFor(', 'ownedLocalPlaybackFile('])
assert 'playbackUrlPrefetchSemaphore.withPermit' in prefetch
assert prefetch.index('playbackUrlPrefetchSemaphore.withPermit') < prefetch.index('PolarisLocalPrefetchGate.lookup')
assert 'putIfAbsent(mediaId, deferred)' in prefetch and 'remove(mediaId, deferred)' in prefetch and 'deferred.cancel()' in prefetch
assert 'publishLabel = false' in prefetch and 'publishLabel = false' in block(s, 'warmPlaybackUrl')
label = block(s, 'publishPlaybackClientLabel')
assert label.index('withContext(Dispatchers.IO)') < label.index('labelWithDeviceFileAttribute(')
assert label.count('isCurrentTrack()') >= 3 and 'revision != playbackLabelRevision' in label
u = (root/updater).read_text(); u_old = old['updater']; u_mask = u
for name in ['schedulePeriodicUpdateCheck', 'cancelPeriodicUpdateCheck', 'checkForUpdates']:
    b = block(u, name)
    assert b.index('!BuildConfig.UPDATER_AVAILABLE') < b.index('WorkManager.getInstance') if 'WorkManager.getInstance' in b else True
    u_old = u_old.replace(block(u_old, name), '<'+name+'>\n')
    u_mask = u_mask.replace(block(u_mask, name), '<'+name+'>\n')
assert u_old == u_mask, 'unreviewed updater change'
assert 'cancelPeriodicUpdateCheck(context)' not in block(u, 'checkForUpdates').split('scope.launch')[0]
h = (root/helper).read_text()
assert 'withContext(Dispatchers.IO)' in h and 'currentCoroutineContext().ensureActive()' in h
assert all(x not in h for x in ['GlobalScope', 'Thread(', 'Executors', 'CoroutineScope(', 'launch('])
assert 'versionName = "50.13"' in (root/'app/build.gradle.kts').read_text()
assert 'versionCode = 100575' in (root/'app/build.gradle.kts').read_text()
report = {'changed_paths':sorted(changed), 'resolver_fallback_audio_queue_radio_unchanged':True,
          'single_flight_semaphore_cancellation_retained':True, 'source_boundary_passed':True,
          'build_generated_asset_changes':{p:current[p] for p in sorted(generated_changes)}}
if sys.argv[1] == 'final':
    suites = [ET.parse(p).getroot() for p in (root/'app/build/test-results/testGmsMobileArm64DebugUnitTest').glob('TEST-*.xml')]
    for name, minimum in new_tests.items():
        found = [v for v in suites if v.get('name', '').endswith('.'+name)]
        assert len(found) == 1 and int(found[0].get('tests','0')) >= minimum, name
        assert sum(int(found[0].get(k,'0')) for k in ['failures','errors','skipped']) == 0, name
    report['executed_new_regressions'] = sum(new_tests.values())
    report['post_fix_cross_validation_passed'] = True
(root/'polaris-cross-validation13.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report))
