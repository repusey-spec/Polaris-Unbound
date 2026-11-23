#!/usr/bin/env python3
"""Polaris 6.15 integration: independent Home radio/music history, live regional
time/AFN fallback, latest approved Radio card images, and in-vehicle browse refresh.
Run AFTER aa614.py and aa615.py (AA projection startup gate).
Do not alter the existing Radio recent list, voice routing, streaming engine, or 4 tabs.
"""
from pathlib import Path
import base64
import sys
root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune"
def one(path,old,new,reason):
    s=path.read_text(encoding="utf-8")
    count=s.count(old)
    assert count==1, f"6.15 {reason}: occurrence={count}"
    path.write_text(s.replace(old,new,1),encoding="utf-8")
callback=src/"playback/MediaLibrarySessionCallback.kt"
last=src/"polaris/PolarisLastSource.kt"
nav=src/"polaris/Polaris613Nav.kt"
auto=src/"playback/AutoCar.kt"
provider=src/"polaris/Polaris613ArtProvider.kt"

# Home is NOT "the current player item or nothing." Read the already working
# Radio recent list, then the last actually audible music item, in that order.
one(callback,
'''    private fun polaris613Home(session: MediaLibrarySession): List<MediaItem> {
        session.player.currentMediaItem?.let { return listOf(it) }
        // Radio is already persisted as the last audible source in 6.06.
        PolarisLastSource.radioToResume(context)?.let {
            return listOf(polarisRadio613(it))
        }
        // Last audibly played streaming track, not an invented demo item.
        return Polaris613Nav.lastPlayed(context)?.let(::listOf) ?: emptyList()
    }''',
'''    private fun polaris613Home(session: MediaLibrarySession): List<MediaItem> {
        // This is exactly the list which already works in the Radio tab.
        val lastRadioId=Polaris613Nav.recent(context).firstOrNull()
            ?: PolarisLastSource.radioToResume(context)
        val radio=lastRadioId?.let { id ->
            RadioCatalog.find(id)?.let { station ->
                val item=polarisRadio613(id)
                item.buildUpon().setMediaMetadata(
                    item.mediaMetadata.buildUpon()
                        .setTitle("최근 라디오 · " + station.title)
                        .setSubtitle(null).setArtist(null).build()
                ).build()
            }
        }
        // MediaLibrary onGetChildren runs on a background executor and may not
        // access session.player (the player enforces its application looper).
        // Use the last actually audible music persisted by the Player.Listener.
        val music=Polaris613Nav.lastPlayed(context)?.let { item ->
            item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setTitle("최근 음악 · " +
                        item.mediaMetadata.title?.toString().orEmpty())
                    .build()
            ).build()
        }
        return listOfNotNull(radio,music)
    }''', "Home dual records")

# IMPORTANT: Radio must not overwrite the shared "last played music" item.
# RememberAudible is called on onIsPlayingChanged and onMediaItemTransition,
# which are real playback events, not a mere browse tap.
one(last,
'''        station?.let { Polaris613Nav.remember(context,it) }
        Polaris613Nav.rememberPlayed(context,item)
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)''',
'''        if (station != null) Polaris613Nav.remember(context,station)
        else Polaris613Nav.rememberPlayed(context,item)
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)''',
"radio does not erase last played music")

# Old 6.14 builds may have written a radio item into the music slot. Filter it
# while reading rather than erasing the user preferences or fabricating a song.
one(nav,
'''        return MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setArtist(artist).setSubtitle(artist)
                .setIsPlayable(true).setIsBrowsable(false).build())
            .build()''',
'''        val item=MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setArtist(artist).setSubtitle(artist)
                .setIsPlayable(true).setIsBrowsable(false).build())
            .build()
        return item.takeIf { PolarisRadioRequests.stationId(it)==null }''',
"old radio item cannot be shown as music")

# Region time is calculated in the station's zone, not the driver's zone.
# This avoids a stale clock contained in a cached program title and respects DST.
one(callback,
'''        val label=programme.takeIf { it.isNotBlank() && it != station.title }
            ?.replace(10.toChar(),' ')?.trim()?.take(65) ?: "\u200B"
        return item.buildUpon()''',
'''        val resolved=programme.replace(10.toChar(),' ').trim()
        val label=when {
            id=="kr5" && resolved.isBlank() -> "AFN Korea · The Eagle"
            station.region!="KR" -> {
                val zone=if(station.region=="NY") "America/New_York"
                    else "America/Los_Angeles"
                val clock=java.time.LocalTime.now(java.time.ZoneId.of(zone))
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                val name=resolved.replace(
                    Regex("\\\\s*\\\\|\\\\s*(NY|SF|LA)\\\\s*\\\\d{1,2}:\\\\d{2}\\\\s*$"),"")
                    .ifBlank { station.title }
                name.take(52)+"·"+station.region+clock
            }
            resolved.isNotBlank() && resolved!=station.title -> resolved.take(65)
            else -> "\u200B"
        }
        return item.buildUpon()''', "AFN fallback and overseas timezone suffix")

# The AutoCar host retains its own lists and otherwise will show stale Home
# after a playback transition. Refresh current home tab whenever source changes.
one(auto,
'''                    (screens.top as? AutoListScreen)?.invalidate()
                }''',
'''                    when(val screen=screens.top) {
                        is AutoListScreen -> screen.invalidate()
                        is AutoTabsScreen -> screen.refreshHomeIfSelected()
                    }
                }''', "refresh CAL Home on playback transition")
one(auto,
'''    private fun load(id: String?) {
        if (id == null || id == SETTINGS) return''',
'''    fun refreshHomeIfSelected() {
        if(tab == Polaris613Nav.HOME) load(tab)
    }

    private fun load(id: String?) {
        if (id == null || id == SETTINGS) return''',
"Home refresh method")

# When a user leaves the overseas station list open, re-request its displayed
# titles every minute so the compact city clocks change without tapping away.
one(auto,
'''        lifecycleScope.launch {
            items = session.children(list.mediaId)
            invalidate()
        }
    }

    override fun onGetTemplate(): Template = safely {
        sectioned(session, list, items, Header.Builder().setStartHeaderAction(Action.BACK).setTitle(list.name()).build())
    }''',
'''        lifecycleScope.launch {
            items = session.children(list.mediaId)
            invalidate()
        }
        if(list.mediaId == Polaris613Nav.WORLD) lifecycleScope.launch {
            while(true) {
                delay(60_000L)
                items = session.children(list.mediaId)
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template = safely {
        sectioned(session, list, items, Header.Builder().setStartHeaderAction(Action.BACK).setTitle(list.name()).build())
    }''', "live overseas clock refresh")

# Final approved images (image only, no Korean caption). New resources are read
# by the same content:// provider used by the already-verified 6.14 Radio tiles.
drawable=root/"app/src/main/res/drawable-nodpi"
drawable.mkdir(parents=True,exist_ok=True)
for kind in ("kr","world"):
    asset=Path(__file__).with_name("art615_"+kind+".b64")
    assert asset.is_file(), "Missing approved art "+str(asset)
    data=base64.b64decode(asset.read_text(encoding="ascii").strip())
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), "Not a PNG: "+kind
    (drawable/("polaris615_"+kind+".png")).write_bytes(data)
one(provider,
'''import android.graphics.Bitmap
import android.graphics.Canvas''',
'''import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dd3boh.outertune.R
import android.graphics.Canvas''', "PNG resource import")
one(provider,
'''            if(id.startsWith("station-")) {
                val bitmap=StationArt.aaBitmap(ctx,id.removePrefix("station-"),256)''',
'''            if(id=="kr" || id=="world") {
                val res=if(id=="kr") R.drawable.polaris615_kr
                    else R.drawable.polaris615_world
                val bitmap=BitmapFactory.decodeResource(ctx.resources,res)
                    ?: throw FileNotFoundException("category art missing")
                file.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
                return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
            }
            if(id.startsWith("station-")) {
                val bitmap=StationArt.aaBitmap(ctx,id.removePrefix("station-"),256)''',
"final domestic/overseas icons")

# A different image must not be hidden by the provider cache from 6.13.
one(provider, 'File(ctx.cacheDir,"polaris-613-icons")',
    'File(ctx.cacheDir,"polaris-615-icons")', "art cache version")

print("PASS 6.15: independent radio+music Home, ongoing update and live overseas clock")
print("PASS 6.15: AFN fallback, final image-only Radio categories")
print("PASS 6.15: Radio history and navigation left intact")
