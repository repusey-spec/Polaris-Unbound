#!/usr/bin/env python3
"""Polaris 6.14 source-only UI repair, applied AFTER aa613.py.
Does not trigger a build. Preserve 6.13 app identity, account and playback.
The release version and signed APK are deliberately NOT changed here.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune"
def change(path,old,new,label):
    s=path.read_text(encoding="utf-8")
    assert s.count(old)==1, f"6.14 {label}: occurrence={s.count(old)}"
    path.write_text(s.replace(old,new,1),encoding="utf-8")

callback=src/"playback/MediaLibrarySessionCallback.kt"
# Show ALL catalogued overseas stations rather than the demo's hardcoded two.
change(callback,
    'listOf("kiis","gallery").map(::polarisRadio613)',
    'RadioCatalog.stations.filter { it.region != "KR" }.map { polarisRadio613(it.id) }',
    "all overseas stations")
# Preserve the last audible item from the existing Polaris playback observer.
change(callback,
'''    private fun polaris613Home(session: MediaLibrarySession): List<MediaItem> {
        val playing=session.player.currentMediaItem ?: return emptyList()
        return listOf(playing)
    }''',
'''    private fun polaris613Home(session: MediaLibrarySession): List<MediaItem> {
        session.player.currentMediaItem?.let { return listOf(it) }
        // Radio is already persisted as the last audible source in 6.06.
        PolarisLastSource.radioToResume(context)?.let {
            return listOf(polarisRadio613(it))
        }
        // Last audibly played streaming track, not an invented demo item.
        return Polaris613Nav.lastPlayed(context)?.let(::listOf) ?: emptyList()
    }''',
    "current-or-last-actually-played Home")
# On Android Auto, source-level artwork URIs work for the large categories;
# the tiny top tabs require direct vector drawable resource URIs.
for id in ("home","radio","streaming","can"):
    change(callback,
           f'Polaris613Art.uri("{id}")',
           f'drawableUri(R.drawable.polaris614_nav_{id})',
           f"top icon {id}")
# Make onGetItem agree with root on the four top-tab icons (prevents stale icons).
change(callback,
'''            return@future LibraryResult.ofItem(browsableMediaItem(mediaId,
                name,null,Polaris613Art.uri(icon),MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),null)''',
'''            val artwork=when(mediaId) {
                Polaris613Nav.HOME -> drawableUri(R.drawable.polaris614_nav_home)
                Polaris613Nav.RADIO -> drawableUri(R.drawable.polaris614_nav_radio)
                Polaris613Nav.STREAM -> drawableUri(R.drawable.polaris614_nav_streaming)
                Polaris613Nav.CAN -> drawableUri(R.drawable.polaris614_nav_can)
                else -> Polaris613Art.uri(icon)
            }
            return@future LibraryResult.ofItem(browsableMediaItem(mediaId,
                name,null,artwork,MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),null)''',
    "onGetItem top tab icon")
# For just the browsed radio cards use bold AA artwork. Do not change phone icon style.
change(callback,
'''                .setArtworkUri(PolarisRadioArtwork.uri(id))
                .build()).build()
    }

    private fun polarisRadioTile''',
'''                .setArtworkUri(Polaris613Art.uri("station-"+id))
                .build()).build()
    }

    private fun polarisRadioTile''',
    "larger station logos for browse cards")

# Observe the ORIGINAL last audible source: this is shared by phone + Android Auto,
# unlike v6.13's onSetMediaItems-only history tracking.
last=src/"polaris/PolarisLastSource.kt"
change(last,
'''        val station = PolarisRadioRequests.stationId(item)
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)''',
'''        val station = PolarisRadioRequests.stationId(item)
        station?.let { Polaris613Nav.remember(context,it) }
        Polaris613Nav.rememberPlayed(context,item)
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)''',
    "actual audible radio history and Home streaming history")

# Existing 613 Nav is kept to preserve the versioned browser IDs and its local
# recent history. Extend it with last played track metadata; no URLs or tokens.
nav=src/"polaris/Polaris613Nav.kt"
change(nav,
'''import android.net.Uri
import com.dd3boh.outertune.BuildConfig''',
'''import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.dd3boh.outertune.BuildConfig''',
    "media imports")
change(nav,
'''    fun recent(context:Context):List<String> =''',
'''    fun rememberPlayed(context:Context,item:MediaItem) {
        val id=item.mediaId.takeIf { it.isNotBlank() } ?: return
        val title=item.mediaMetadata.title?.toString()?.trim().orEmpty()
        if(title.isBlank())return
        context.getSharedPreferences("polaris_last_played_614",Context.MODE_PRIVATE).edit()
            .putString("media_id",id)
            .putString("title",title.take(160))
            .putString("artist",item.mediaMetadata.artist?.toString()?.take(160).orEmpty())
            .apply()
    }
    fun lastPlayed(context:Context):MediaItem? {
        val p=context.getSharedPreferences("polaris_last_played_614",Context.MODE_PRIVATE)
        val id=p.getString("media_id",null)?.takeIf { it.isNotBlank() } ?: return null
        val title=p.getString("title",null)?.takeIf { it.isNotBlank() } ?: return null
        val artist=p.getString("artist","").orEmpty()
        return MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setArtist(artist).setSubtitle(artist)
                .setIsPlayable(true).setIsBrowsable(false).build())
            .build()
    }
    fun recent(context:Context):List<String> =''',
    "last audible music in Home")

# Explicit vector images for tabs in the CAL host; do not depend on the
# provider's content:// image loading at tab size.
auto=src/"playback/AutoCar.kt"
change(auto,
'''            out.addTab(Tab.Builder().setContentId(item.mediaId).setTitle(item.name()).setIcon(carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note).first).build())''',
'''            val icon=when(item.mediaId) {
                Polaris613Nav.HOME -> carContext.icon(R.drawable.polaris614_nav_home)
                Polaris613Nav.RADIO -> carContext.icon(R.drawable.polaris614_nav_radio)
                Polaris613Nav.STREAM -> carContext.icon(R.drawable.polaris614_nav_streaming)
                Polaris613Nav.CAN -> carContext.icon(R.drawable.polaris614_nav_can)
                else -> carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note).first
            }
            out.addTab(Tab.Builder().setContentId(item.mediaId).setTitle(item.name()).setIcon(icon).build())''',
    "top CAL tabs use packaged drawable icons")

# Seed the recent menu from the EXISTING last-audible-radio record, so the
# first update can show the last heard channel without another playback.
change(callback,
'''        val recent=Polaris613Nav.recent(context)
            .mapNotNull { RadioCatalog.find(it)?.let { _ -> polarisRadio613(it) } }''',
'''        val recent=(listOfNotNull(PolarisLastSource.radioToResume(context)) +
            Polaris613Nav.recent(context)).distinct()
            .mapNotNull { RadioCatalog.find(it)?.let { _ -> polarisRadio613(it) } }''',
    "seed recent list from actual previously played radio")

# The original bitmap service remains for legacy media sources. Add high-
# contrast, big, bold versions for browse cards only.
station=src/"polaris/StationArt.java"
s=station.read_text(encoding="utf-8")
assert s.count("    private static Spec spec(String id){")==1
s=s.replace("    private static Spec spec(String id){",'''    public static Bitmap aaBitmap(Context context,String id,int size){
        Spec spec=spec(id);
        Bitmap full=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(full);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(spec.bg);
        canvas.drawRoundRect(new RectF(0,0,512,512),42f,42f,paint);
        paint.setColor(spec.fg);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        aaText(canvas,paint,spec.top,115f,146f);
        aaText(canvas,paint,spec.mid,170f,310f);
        aaText(canvas,paint,spec.bottom,84f,424f);
        if(size==512)return full;
        Bitmap scaled=Bitmap.createScaledBitmap(full,size,size,true);
        full.recycle();
        return scaled;
    }
    private static void aaText(Canvas canvas,Paint paint,String text,float size,float y){
        paint.setTextSize(size);
        float width=paint.measureText(text);
        if(width>468f)paint.setTextSize(size*468f/width);
        canvas.drawText(text,256f,y,paint);
    }

    private static Spec spec(String id){''',1)
station.write_text(s,encoding="utf-8")
provider=src/"polaris/Polaris613ArtProvider.kt"
change(provider,
'''        return id.takeIf { names.containsKey(it) }''',
'''        if(id.startsWith("station-") &&
            RadioCatalog.find(id.removePrefix("station-"))!=null)return id
        return id.takeIf { names.containsKey(it) }''',"browse station art ids")
change(provider,
'''            val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)''',
'''            if(id.startsWith("station-")) {
                val bitmap=StationArt.aaBitmap(ctx,id.removePrefix("station-"),256)
                file.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
                return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
            }
            val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)''',"bold broadcast logo files")

# Host-independent, minimal vector icons. Same color/weight at a small tab
# size and no words crammed into a 24dp glyph.
drawables=root/"app/src/main/res/drawable"
drawables.mkdir(parents=True,exist_ok=True)
paths={
"home":"M3,10 L12,3 L21,10 M5,9 L5,21 L19,21 L19,9 M9,21 L9,14 L15,14 L15,21",
"radio":"M5,10 L19,10 L19,20 L5,20 Z M5,10 L8,4 L17,4 L19,10 M9,14 L9,16 M14,14 L17,14 M14,17 L17,17",
"streaming":"M9,4 L9,17 M9,17 C7,15 3,16 3,19 C3,22 9,22 9,19 M9,5 L20,3 L20,16 M20,16 C18,14 14,15 14,18 C14,21 20,21 20,18",
"can":"M12,3 L12,6 M5,7 L7,9 M19,7 L17,9 M4,19 A9,9 0,0 1,20 19 M12,18 L16,12"
}
for name,path in paths.items():
    (drawables/f"polaris614_nav_{name}.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF" android:strokeWidth="2.0"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="{path}" />
</vector>
''',encoding="utf-8")
print("6.14 SOURCE PREPARED ONLY: 4 vector tab icons; catalogue overseas stations;")
print("phone+AA audible recent radio; Home current/last music; readable station art.")
# 6.13's regression test intentionally asserted the incorrect 2-station demo;
# this expected count must change to the actual 7 overseas catalog entries.
test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisAA613BrowserTest.kt"
if test.exists():
    change(test, 'assertEquals(2,get("polaris:radio:world:v613").size)',
           'assertEquals(7,get("polaris:radio:world:v613").size)',
           "updated overseas regression count")
print("No APK build, version increment, signing, or release performed.")
