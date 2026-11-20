#!/usr/bin/env python3
"""Source-level guard for 6.14 real radio/station history/Home/icons and version."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune"
c=(src/"playback/MediaLibrarySessionCallback.kt").read_text()
nav=(src/"polaris/Polaris613Nav.kt").read_text()
last=(src/"polaris/PolarisLastSource.kt").read_text()
auto=(src/"playback/AutoCar.kt").read_text()
station=(src/"polaris/StationArt.java").read_text()
provider=(src/"polaris/Polaris613ArtProvider.kt").read_text()
test=(root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisAA613BrowserTest.kt").read_text()
gradle=(root/"app/build.gradle.kts").read_text()
checks={
"4 tab drawable IDs":all(f'R.drawable.polaris614_nav_{i}' in c and f'R.drawable.polaris614_nav_{i}' in auto for i in ('home','radio','streaming','can')),
"4 packaged vector icons":all((root/f'app/src/main/res/drawable/polaris614_nav_{i}.xml').is_file() for i in ('home','radio','streaming','can')),
"7 overseas stations":'RadioCatalog.stations.filter { it.region != "KR" }' in c and 'assertEquals(7,get("polaris:radio:world:v613").size)' in test,
"radio now and persistent recent":'PolarisLastSource.radioToResume(context)' in c and 'Polaris613Nav.remember(context,it)' in last,
"home current and last played":'session.player.currentMediaItem?.let { return listOf(it) }' in c and 'Polaris613Nav.lastPlayed(context)' in c and 'Polaris613Nav.rememberPlayed(context,item)' in last,
"bold browse station logo":'StationArt.aaBitmap' in provider and 'public static Bitmap aaBitmap' in station,
"CAN empty":'Polaris613Nav.CAN -> emptyList()' in c,
"same package and version":'applicationId = "app.hush.music.polarisradio.debug"' in gradle and 'versionCode = 100614' in gradle and 'versionName = "6.14"' in gradle,
"real liked list still present":'carLikedSongs()' in c and 'playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID)' in c
}
for k,v in checks.items():print(("PASS" if v else "FAIL")+" "+k)
assert all(checks.values()),"6.14 source validation failure"
