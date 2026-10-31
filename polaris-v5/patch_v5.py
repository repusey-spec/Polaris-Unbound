#!/usr/bin/env python3
"""Polaris 5.0 CLEAN overlay for upstream Hush v13.14.8.

No polaris-hush scripts, 49..57 patches, old Hush clone or NAS/CAN source.
Three exact v0.58 Java classes come from the frozen v0.58 branch checkout:
RadioPresets, CurrentProgramResolver and StationArt.
"""
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1]).resolve()
repo = Path(__file__).resolve().parents[1]
base = root / "app/src/main/kotlin/app/hush/music"
callback = base / "playback/MediaLibrarySessionCallback.kt"
service = base / "playback/MusicService.kt"
main = base / "MainActivity.kt"
navigation = base / "ui/screens/NavigationBuilder.kt"
gradle = root / "app/build.gradle.kts"
manifest = root / "app/src/main/AndroidManifest.xml"
assert "13.14.8" in gradle.read_text(), "Requires current Hush v13.14.8"
assert (repo / "app/src/main/java/com/polarisunbound/app/RadioPresets.java").exists()

def replace_unique(src: str, old: str, new: str, label: str) -> str:
    count = src.count(old)
    assert count == 1, f"{label} expected 1 source anchor, got {count}"
    return src.replace(old, new, 1)

# Stage unmodified legacy radio data and our new self-contained integration.
java_dir = root / "app/src/main/java/com/polarisunbound/app"
java_dir.mkdir(parents=True, exist_ok=True)
for name in ("RadioPresets.java", "CurrentProgramResolver.java", "StationArt.java"):
    shutil.copyfile(repo / ("app/src/main/java/com/polarisunbound/app/" + name), java_dir / name)
kotlin_dir = base / "polarisv5"
kotlin_dir.mkdir(parents=True, exist_ok=True)
for name in ("V5Radio.kt", "V5StationArtProvider.kt", "V5PhoneRadio.kt"):
    shutil.copyfile(Path(__file__).parent / name, kotlin_dir / name)

# AA root has two browsable source indexes. Streaming is Hush's ORIGINAL root
# branch, not a duplicated /handmade playlist tree. All child identities and
# song selection logic below it remain upstream Hush.
c = callback.read_text()
c = replace_unique(c,
    "package app.hush.music.playback\n",
    "package app.hush.music.playback\n\nimport app.hush.music.polarisv5.V5Radio\n",
    "AA radio import",
)
root_branch = "                        MusicService.ROOT -> {"
radio_branches = """                        MusicService.ROOT -> {
                            listOf(
                                browsableMediaItem(
                                    V5Radio.ROOT, "Radio", null,
                                    drawableUri(R.drawable.radio),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                                browsableMediaItem(
                                    V5Radio.STREAMING, "Streaming", null,
                                    drawableUri(R.drawable.queue_music),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                            )
                        }

                        V5Radio.ROOT -> V5Radio.browse(context)

                        V5Radio.STREAMING -> {"""
c = replace_unique(c, root_branch, radio_branches, "two source roots")
# AA needs onGetItem for every browsable/selected entry. Do not return
# ERROR_UNKNOWN for Radio or a station after the user taps it.
get_anchor = """                when {
                    mediaId == MusicService.ROOT -> {"""
get_replacement = """                when {
                    mediaId == V5Radio.ROOT || mediaId == V5Radio.STREAMING -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                mediaId,
                                if (mediaId == V5Radio.ROOT) "Radio" else "Streaming",
                                null,
                                drawableUri(if (mediaId == V5Radio.ROOT) R.drawable.radio else R.drawable.queue_music),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            null,
                        )
                    }

                    V5Radio.isRadio(mediaId) -> {
                        V5Radio.idFromMedia(mediaId)?.let {
                            LibraryResult.ofItem(V5Radio.item(context, it), null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    mediaId == MusicService.ROOT -> {"""
c = replace_unique(c, get_anchor, get_replacement, "all AA sources are getItem retrievable")

set_anchor = """                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult"""
set_replacement = """                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                val requestedRadioId =
                    mediaItems.getOrNull(startIndex.coerceIn(mediaItems.indices))
                        ?.mediaId?.let(V5Radio::idFromMedia)
                if (requestedRadioId != null) {
                    val radioQueue = V5Radio.queue(context)
                    val selectedIndex = radioQueue.indexOfFirst {
                        it.mediaId == V5Radio.STATION_PREFIX + requestedRadioId
                    }
                    check(selectedIndex >= 0) { "Radio station not in source queue" }
                    return@future MediaSession.MediaItemsWithStartPosition(
                        radioQueue, selectedIndex, 0L,
                    )
                }"""
c = replace_unique(c, set_anchor, set_replacement, "AA station selection -> 13 item queue")
callback.write_text(c)

# Keep Hush's whole Media3 stream/player implementation intact. One routing
# hook is enough: radio:// logical URI is resolved at actual load time,
# preserving the 13-item queue while keeping stale broadcaster tokens out.
s = service.read_text()
s = replace_unique(s,
    "package app.hush.music.playback\n",
    "package app.hush.music.playback\n\nimport app.hush.music.polarisv5.V5Radio\n",
    "radio datasource import")
route_anchor = """        return ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            resolvePlaybackDataSpec(dataSpec = dataSpec)
        }"""
route_replacement = """        return ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            if (dataSpec.uri.scheme == V5Radio.SCHEME) {
                val id = dataSpec.uri.lastPathSegment
                    ?: throw java.io.IOException("Radio station URI incomplete")
                dataSpec.withUri(V5Radio.resolve(id))
            } else {
                resolvePlaybackDataSpec(dataSpec = dataSpec)
            }
        }"""
assert s.count(route_anchor) >= 1
s = s.replace(route_anchor, route_replacement, 1)
# A radio station is not a YouTube track; skip song history, lyrics, SpotiFLAC
# prefetch and its resolver. Native player/AA state has already updated.
transition_anchor = """        super.onMediaItemTransition(mediaItem, reason)
        val reasonLabel = when (reason) {"""
transition_replacement = """        super.onMediaItemTransition(mediaItem, reason)
        if (V5Radio.isRadio(mediaItem?.mediaId)) {
            updateNotification()
            return
        }
        val reasonLabel = when (reason) {"""
s = replace_unique(s, transition_anchor, transition_replacement, "protect radio from Hush track prefetch")
# Media3 controls prior/next use the actual 13-item queue. Suppress music-only
# like/repeat/shuffle/add buttons during radio; preserve all for Streaming.
s = replace_unique(s,
    """            val customLayout =
                listOf(""",
    """            val customLayout =
                if (V5Radio.isRadio(player.currentMediaItem?.mediaId))
                    emptyList<CommandButton>()
                else listOf(""",
    "radio notification buttons")
# Do not submit station IDs to Hush's failed YouTube-source retry engine.
error_anchor = """        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        val isLocalMedia = currentMediaId.isLocalMediaId()"""
error_replacement = """        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        if (V5Radio.isRadio(currentMediaId)) {
            // Media3's upstream loader performs normal HLS/network retry.
            // Never trigger Hush/YouTube recovery for a radio station.
            return
        }
        val isLocalMedia = currentMediaId.isLocalMediaId()"""
s = replace_unique(s,error_anchor,error_replacement,"protect radio error path")
# Start schedule refresh without modifying player timeline during callbacks.
start_anchor = """        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF"""
start_replacement = """        updateNotification()
        V5Radio.beginProgrammeRefresh(this)
        player.repeatMode = REPEAT_MODE_OFF"""
s = replace_unique(s,start_anchor,start_replacement,"programme scheduler")
service.write_text(s)

# Phone route and app-wide two source indexes. No cloned music player or
# custom streaming DB. Hush Home stays the true Streaming route.
n = navigation.read_text()
n = replace_unique(n,
    "    composable(Screens.ROUTE_HOME) {",
    """    composable(app.hush.music.polarisv5.V5_RADIO_ROUTE) {
        app.hush.music.polarisv5.V5RadioScreen()
    }
    composable(Screens.ROUTE_HOME) {""",
    "phone radio navigation")
navigation.write_text(n)
m = main.read_text()
m = replace_unique(m,
    "                                                NavigationTab.HOME -> Screens.ROUTE_HOME",
    "                                                NavigationTab.HOME -> app.hush.music.polarisv5.V5_RADIO_ROUTE",
    "phone launch defaults to Radio")
m = replace_unique(m,
    """                                topBar = {
                                    if (shouldShowTopBar) {""",
    """                                topBar = {
                                    Column {
                                        app.hush.music.polarisv5.V5SourceTabs(navController, currentRoute)
                                        if (shouldShowTopBar) {""",
    "phone shared source index header")
m = replace_unique(m,
    """                                    }
                                },
                                bottomBar = {""",
    """                                        }
                                    }
                                },
                                bottomBar = {""",
    "phone source index header closure")
main.write_text(m)

# Fresh v5 app ID is intentionally side-by-side with previous Polaris 0.x
# and v49..57: no forced uninstall, data wipe or version downgrade.
g=gradle.read_text()
g=replace_unique(g,
    'applicationIdSuffix = ".debug"',
    'applicationIdSuffix = ".polarisv5"',
    "new app identity")
g=replace_unique(g,'versionCode = 178','versionCode = 500000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)',"v5 versionCode")
g=replace_unique(g,'versionName = "13.14.8"','versionName = "5.0.0"',"v5 versionName")
g=replace_unique(g,
    '        getByName("debug") {\n            enableV1Signing = true',
    '        getByName("debug") {\n            storeFile = rootProject.file("polaris-v5-debug.keystore")\n            storePassword = "android"\n            keyAlias = "polarisdebug"\n            keyPassword = "android"\n            enableV1Signing = true',
    "persistent v5 signing")
# Live source schedules use the exact v0.58 jsoup parser.
g=replace_unique(g,'    implementation(libs.guava)\n','    implementation(libs.guava)\n    implementation("org.jsoup:jsoup:1.18.3")\n',"v0.58 schedules")
gradle.write_text(g)

m=manifest.read_text()
assert m.count('android:label="@string/app_name"') >= 1
m=m.replace('android:label="@string/app_name"','android:label="Polaris v5"')
provider = """        <provider
            android:name="app.hush.music.polarisv5.V5StationArtProvider"
            android:authorities="\u0024{applicationId}.v5art"
            android:exported="true"
            android:grantUriPermissions="true" />
"""
m=replace_unique(m,
    '        <service\n            android:name=".playback.MusicService"',
    provider+'        <service\n            android:name=".playback.MusicService"',
    "actual station artwork provider")
manifest.write_text(m)
# All project branding is patched independently by icon_v5.py.
print("Polaris v5.0 source assembled from Hush v13.14.8 and frozen v0.58 radio only")
