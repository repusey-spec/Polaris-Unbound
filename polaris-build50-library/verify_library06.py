#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1]); base = root / 'app/src/main/kotlin/app/hush/music'
car = base / 'polaris'
ui = (car / 'PolarisCarScreens.kt').read_text()
client = (car / 'PolarisCarClient.kt').read_text()
cb = (base / 'playback/MediaLibrarySessionCallback.kt').read_text()
svc = (base / 'playback/MusicService.kt').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
gradle = (root / 'app/build.gradle.kts').read_text()
ET.fromstring(manifest)
checks = {
 'new media template service has host API 8 and permission': 'PolarisCarService' in manifest and 'androidx.car.app.MEDIA_TEMPLATES' in manifest and 'android:value="8"' in manifest,
 'media browser and template discovery coexist': '<uses name="media"' in (root / 'app/src/main/res/xml/automotive_app_desc.xml').read_text() and '<uses name="template"' in (root / 'app/src/main/res/xml/automotive_app_desc.xml').read_text(),
 'car UI uses existing session, no second player': 'ComponentName(context, MusicService::class.java)' in client and 'ExoPlayer.Builder' not in client and 'registerMediaPlaybackToken' in client,
 'session token only supplied to same app': 'controller.packageName != context.packageName' in cb and 'session.platformToken' in cb,
 'list click only navigates opposite browse destination': 'fun openOpposite()' in ui and 'PolarisCarNavigation.opposite(catalog.currentId)' in ui,
 'no timeline mutation in new UI': 'setMediaItems(' not in ui and 'setMediaItem(' not in ui and 'replaceMediaItem(' not in ui,
 'folders browse, playable items selected explicitly': 'item.mediaMetadata.isBrowsable == true' in ui and 'catalog.play(item)' in ui,
 'search is actual SearchTemplate routed through Hush browser': 'SearchTemplate.Builder(callback)' in ui and '.getSearchResult(query, 0, size, null)' in client and 'VOICE_ASSIST' not in ui,
 'player token is obtained before playback template becomes ready': 'browser != null && registered' in client,
 'previous full radio queues and malformed URL fixes retained': 'PolarisRadioPlaylist.set' in svc and 'PolarisRadioRequests.resolve(spec)' in svc,
 'next-track errors include queue and transition evidence': 'queue-near-error index=' in svc and 'transition reason=' in svc,
 'one failed track cannot cancel the shared service job': svc.count('scopeJob = app.hush.music.polaris.PolarisPlaybackJobs.createServiceJob()') == 2 and 'scopeJob = Job()' not in svc,
 'background failures are isolated and identified by their own track': 'PolarisPlaybackJobs.prefetch(mediaId' in svc and 'prefetch id=' in svc,
 'updated version with original package': 'versionCode = 100568' in gradle and 'versionName = "50.6"' in gradle and 'applicationIdSuffix = ".polarisradio.debug"' in gradle,
}
for name, value in checks.items(): print(('PASS' if value else 'FAIL') + ': ' + name)
assert all(checks.values())
