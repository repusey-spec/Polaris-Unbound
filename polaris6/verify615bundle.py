#!/usr/bin/env python3
"""Runtime source contract for Polaris 6.15 after all integration stages."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
callback=(pkg/"playback/MediaLibrarySessionCallback.kt").read_text()
auto=(pkg/"playback/AutoCar.kt").read_text()
nav=(pkg/"polaris/Polaris613Nav.kt").read_text()
last=(pkg/"polaris/PolarisLastSource.kt").read_text()
provider=(pkg/"polaris/Polaris613ArtProvider.kt").read_text()
service=(pkg/"playback/MusicService.kt").read_text()
gradle=(root/"app/build.gradle.kts").read_text()
checks={
"Home reads already working Radio recent":'Polaris613Nav.recent(context).firstOrNull()' in callback,
"Home returns radio plus music separately":'return listOfNotNull(radio,music)' in callback,
"Home recognizes active radio/streaming":'PolarisRadioRequests.stationId(it)==null' in callback,
"last audible radio no longer replaces music":'else Polaris613Nav.rememberPlayed(context,item)' in last,
"old radio record filtered from music":'PolarisRadioRequests.stationId(it)==null' in nav,
"real source change refreshes Home":'screen.refreshHomeIfSelected()' in auto,
"browse changes on return":'override fun onStart(owner: LifecycleOwner) = load(tab)' in auto,
"AFN program fallback":'"AFN Korea · The Eagle"' in callback,
"overseas zones with DST":'America/New_York' in callback and 'America/Los_Angeles' in callback,
"overseas compact city clock":'name.take(52)+"·"+station.region+clock' in callback,
"overseas clock updates":'if(list.mediaId == Polaris613Nav.WORLD)' in auto and 'delay(60_000L)' in auto,
"original Radio recent list intact":'Polaris613Nav.recent(context)).distinct()' in callback,
"two image-only categories":'browsableMediaItem(Polaris613Nav.KR, "\u200B"' in callback and 'browsableMediaItem(Polaris613Nav.WORLD, "\u200B"' in callback,
"selected image resources installed":all((root/f"app/src/main/res/drawable-nodpi/polaris615_{id}.png").stat().st_size>3000 for id in ("kr","world")),
"provider serves approved images":'R.drawable.polaris615_kr' in provider and 'R.drawable.polaris615_world' in provider,
"no old category cache":'polaris-615-icons' in provider,
"four tabs preserved":all(f'R.drawable.polaris614_nav_{i}' in auto for i in ("home","radio","streaming","can")),
"CAN blank":'Polaris613Nav.CAN -> emptyList()' in callback,
"no new action row / stars":'row.addAction(Action.MEDIA_PLAYBACK)' in auto and 'favorite' not in callback.split('private fun polaris613Home(')[1].split('    }')[0],
"AA waits for projection":'polarisWaitForAaProjection()' in service and 'CONNECTION_TYPE_PROJECTION' in service,
"AA automatic resume delayed 2.5sec":'polarisAaStartDelayMs = 2500L' in service,
"still uses original streaming engine":'playlists' in callback and 'carLikedSongs()' in callback,
"signed 6.15 identity":'applicationId = "app.hush.music.polarisradio.debug"' in gradle and 'versionCode = 100615' in gradle and 'versionName = "6.15"' in gradle,
}
for k,v in checks.items():print(("PASS" if v else "FAIL")+" "+k)
assert all(checks.values()), "6.15 failed source-contract check"
