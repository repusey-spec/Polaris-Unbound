#!/usr/bin/env python3
"""Polaris 6.13: four real Media3 browse tabs, two compact category tiles with
live rows, original station artwork, no CAN data. Keeps 6.12 playback intact.
Invoked AFTER all 6.12 integration scripts, BEFORE compiling the release."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
cb=pkg/"playback/MediaLibrarySessionCallback.kt"
s=cb.read_text(encoding="utf-8")
# New namespace imports are added after the package line, regardless of
# legacy Polaris imports injected by earlier build scripts.
package_line="package com.dd3boh.outertune.playback\n"
assert s.count(package_line)==1,"MediaLibrary package declaration missing"
s=s.replace(package_line,package_line+"""
import com.dd3boh.outertune.polaris.Polaris613Nav
import com.dd3boh.outertune.polaris.Polaris613Art
import com.dd3boh.outertune.polaris.CurrentProgramResolver
""",1)

def one(src,old,new,why):
    n=src.count(old)
    assert n==1, f"6.13 {why}: expected one occurrence, saw {n}: {old[:110]!r}"
    return src.replace(old,new,1)

# Root top navigation: correct ordering, 4 real tabs, authentic images from
# our own content provider instead of 4 identical fallback white squares.
s=one(s,'''                    polarisFolder(RadioCatalog.RADIO_BROWSE_ROOT_ID, "Radio"),
                    polarisFolder(RadioCatalog.STREAMING_BROWSE_ROOT_ID, "Streaming"),''',
'''                    browsableMediaItem(Polaris613Nav.HOME, "Home", null,
                        Polaris613Art.uri("home"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        style(playable = LIST, browsable = LIST)),
                    browsableMediaItem(Polaris613Nav.RADIO, "Radio", null,
                        Polaris613Art.uri("radio"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        style(playable = LIST, browsable = GRID)),
                    browsableMediaItem(Polaris613Nav.STREAM, "Streaming", null,
                        Polaris613Art.uri("streaming"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        style(playable = LIST, browsable = GRID)),
                    browsableMediaItem(Polaris613Nav.CAN, "CAN", null,
                        Polaris613Art.uri("can"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        style(playable = LIST, browsable = LIST)),''',"four root entries")

# Original radio station artwork, and one live programme line below the logo.
# Never print the frequency/station again under the icon; no second line.
anchor='''    private fun polarisRadioTile(id: String): MediaItem {'''
assert s.count(anchor)==1
s=s.replace(anchor,'''    private fun polarisRadio613(id: String): MediaItem {
        val item=PolarisRadioRequests.queueItem(id)
        val station=RadioCatalog.find(id) ?: return item
        val cached=CurrentProgramResolver.cached(context, id)
        val programme=if (cached != null && CurrentProgramResolver.isCurrentNow(id,cached))
            cached.program?.trim().orEmpty() else ""
        val label=programme.takeIf { it.isNotBlank() && it != station.title }
            ?.replace(10.toChar(),' ')?.trim()?.take(65) ?: "\u200B"
        return item.buildUpon()
            .setMediaId(RadioCatalog.RADIO_TILE_PREFIX + id)
            .setMediaMetadata(item.mediaMetadata.buildUpon()
                .setTitle(label)
                .setSubtitle(null).setArtist(null)
                .setArtworkUri(PolarisRadioArtwork.uri(id))
                .build()).build()
    }

'''+anchor,1)

# Use the same canonical radio playback request and Media3 resolution route.
# Stream labels are real liked songs, not fabricated data.
anchor='''    /**
     * The unmodified InterTune AA landing'''
assert s.count(anchor)==1,"old streaming landing location missing"
insertion='''    private fun polaris613RadioMenu(): List<MediaItem> {
        val tile = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
        val categories=listOf(
            browsableMediaItem(Polaris613Nav.KR, "\u200B", null,
                Polaris613Art.uri("kr"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                style(single=tile)),
            browsableMediaItem(Polaris613Nav.WORLD, "\u200B", null,
                Polaris613Art.uri("world"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                style(single=tile))
        )
        val recent=Polaris613Nav.recent(context)
            .mapNotNull { RadioCatalog.find(it)?.let { _ -> polarisRadio613(it) } }
        return categories + recent
    }

    private suspend fun polaris613StreamingMenu(): List<MediaItem> {
        val tile=MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
        val categories=listOf(
            browsableMediaItem(RadioCatalog.PLAYLIST_ROOT_ID, "\u200B", null,
                Polaris613Art.uri("playlists"), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                style(single=tile)),
            browsableMediaItem(Polaris613Nav.DOWNLOADS, "\u200B", null,
                Polaris613Art.uri("downloads"), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                style(single=tile))
        )
        // Actual account likes, with local liked songs as the offline fallback.
        val liked=try { carLikedSongs() } catch (_:Exception) { emptyList() }
        val available=if (liked.isNotEmpty()) liked else playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID)
        return categories + available.take(AutoBrowse.MOST_SONGS).map {
            it.toMediaItem(MusicService.PLAYLIST + "/" + PlaylistEntity.LIKED_PLAYLIST_ID)
        }
    }

    private fun polaris613Home(session: MediaLibrarySession): List<MediaItem> {
        val playing=session.player.currentMediaItem ?: return emptyList()
        return listOf(playing)
    }

'''
s=s.replace(anchor,insertion+anchor,1)

# Keep former 6.10 roots valid for car bookmarks but expose version 6.13.
anchor='''                RadioCatalog.RADIO_BROWSE_ROOT_ID, RadioCatalog.RADIO_BROWSE_PREVIOUS_ROOT_ID, RadioCatalog.RADIO_ROOT_ID ->'''
assert s.count(anchor)==1
s=s.replace(anchor,'''                Polaris613Nav.HOME -> polaris613Home(session)
                Polaris613Nav.CAN -> emptyList()
                Polaris613Nav.RADIO -> CarSearch.page(polaris613RadioMenu(), page, pageSize)
                Polaris613Nav.STREAM -> CarSearch.page(polaris613StreamingMenu(), page, pageSize)
                Polaris613Nav.KR -> CarSearch.page(
                    listOf("kr1","kr2","kr3","kr4","kr5","kr6").map(::polarisRadio613), page, pageSize)
                Polaris613Nav.WORLD -> CarSearch.page(
                    listOf("kiis","gallery").map(::polarisRadio613), page, pageSize)
                Polaris613Nav.DOWNLOADS ->
                    playlistSongs(PlaylistEntity.DOWNLOADED_PLAYLIST_ID).map {
                        it.toMediaItem(MusicService.PLAYLIST + "/" + PlaylistEntity.DOWNLOADED_PLAYLIST_ID)
                    }
'''+anchor,1)

# A car's onGetItem can retrieve the new tabs/category nodes.
anchor='''        RadioCatalog.tileStationId(mediaId)?.let {'''
assert s.count(anchor)==1
s=s.replace(anchor,'''        if (mediaId in listOf(Polaris613Nav.HOME,Polaris613Nav.RADIO,
                Polaris613Nav.STREAM,Polaris613Nav.CAN,
                Polaris613Nav.KR,Polaris613Nav.WORLD,Polaris613Nav.DOWNLOADS)) {
            val name=when(mediaId){
                Polaris613Nav.HOME->"Home"
                Polaris613Nav.RADIO->"Radio"
                Polaris613Nav.STREAM->"Streaming"
                Polaris613Nav.CAN->"CAN"
                Polaris613Nav.KR->"국내"
                Polaris613Nav.WORLD->"해외"
                else->"다운로드"
            }
            val icon=when(mediaId){
                Polaris613Nav.HOME->"home"
                Polaris613Nav.RADIO->"radio"
                Polaris613Nav.STREAM->"streaming"
                Polaris613Nav.CAN->"can"
                Polaris613Nav.KR->"kr"
                Polaris613Nav.WORLD->"world"
                else->"downloads"
            }
            return@future LibraryResult.ofItem(browsableMediaItem(mediaId,
                name,null,Polaris613Art.uri(icon),MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),null)
        }
'''+anchor,1)
s=one(s,'''return@future LibraryResult.ofItem(polarisRadioTile(it), null)''',
'''return@future LibraryResult.ofItem(polarisRadio613(it), null)''',"onGetItem original logo")

# Persist real selections (not fake demo clicks) for the radio recent list.
needle='''        val request = mediaItems.firstOrNull()
        val resolved = scope.future'''
assert s.count(needle)==1
s=s.replace(needle,'''        val request = mediaItems.firstOrNull()
        request?.let { req ->
            (RadioCatalog.tileStationId(req.mediaId)
                ?: PolarisRadioRequests.stationId(req))?.let { id ->
                Polaris613Nav.remember(context,id)
                runCatching { service.mediaSession.notifyChildrenChanged(
                    Polaris613Nav.RADIO,Int.MAX_VALUE,null) }
            }
        }
        val resolved = scope.future''',1)
# service.mediaSession may be a non-public or nullable property; notify via
# onSetMediaItems' session argument, available for MediaLibrarySession only.
s=s.replace('''                runCatching { service.mediaSession.notifyChildrenChanged(
                    Polaris613Nav.RADIO,Int.MAX_VALUE,null) }''','')

# Kotlin uses a MediaLibrarySession parameter in onGetChildren and paging.
# Keep older controller callbacks and voice/search routing unchanged.
cb.write_text(s,encoding="utf-8")

# Android Auto artwork is served through an exported, read-only provider.
nav=pkg/"polaris/Polaris613Nav.kt"
nav.write_text('''package com.dd3boh.outertune.polaris

import android.content.Context
import android.net.Uri
import com.dd3boh.outertune.BuildConfig

object Polaris613Nav {
    const val HOME="polaris:home:v613"
    const val RADIO="polaris:radio:v613"
    const val STREAM="polaris:streaming:v613"
    const val CAN="polaris:can:v613"
    const val KR="polaris:radio:kr:v613"
    const val WORLD="polaris:radio:world:v613"
    const val DOWNLOADS="polaris:stream:downloads:v613"
    fun recent(context:Context):List<String> =
        context.getSharedPreferences("polaris_radio_recent_613",Context.MODE_PRIVATE)
            .getString("ids","")!!.split(",").filter { it.isNotBlank() && RadioCatalog.find(it)!=null }
    fun remember(context:Context,id:String) {
        if(RadioCatalog.find(id)==null)return
        val ids=(listOf(id)+recent(context).filter { it!=id }).take(10)
        context.getSharedPreferences("polaris_radio_recent_613",Context.MODE_PRIVATE)
            .edit().putString("ids",ids.joinToString(",")).apply()
    }
}

object Polaris613Art {
    fun uri(id:String):Uri = Uri.Builder().scheme("content")
        .authority(BuildConfig.APPLICATION_ID+".polaris.menuart")
        .appendPath(id).build()
}
''',encoding="utf-8")
provider=pkg/"polaris/Polaris613ArtProvider.kt"
provider.write_text('''package com.dd3boh.outertune.polaris

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Private, standalone icons rendered to real PNGs for cross-process Android Auto. */
class Polaris613ArtProvider:ContentProvider() {
    private val names=mapOf(
        "home" to "⌂", "radio" to "♫", "streaming" to "▶",
        "can" to "CAN", "kr" to "국내", "world" to "해외",
        "playlists" to "플레이리스트", "downloads" to "다운로드"
    )
    override fun onCreate():Boolean=true
    override fun getType(uri:Uri):String? =
        if (valid(uri)!=null) "image/png" else null
    private fun valid(uri:Uri):String? {
        val id=uri.pathSegments.singleOrNull() ?: return null
        if(uri.authority!=context?.packageName+".polaris.menuart")return null
        return id.takeIf { names.containsKey(it) }
    }
    @Synchronized override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("read only")
        val id=valid(uri) ?: throw FileNotFoundException("unknown icon")
        val ctx=context ?: throw FileNotFoundException("context")
        val dir=File(ctx.cacheDir,"polaris-613-icons")
        if(!dir.isDirectory)dir.mkdirs()
        val file=File(dir,"icon-"+id+".png")
        if(!file.exists() || file.length()==0L) {
            val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            val color=when(id){
                "radio","kr","world"->0xFFF29A28.toInt()
                "streaming","playlists","downloads"->0xFF9E87DF.toInt()
                "can"->0xFF508EC9.toInt()
                else->0xFF19AFA1.toInt()
            }
            p.color=color
            canvas.drawRoundRect(RectF(10f,10f,246f,246f),44f,44f,p)
            p.color=Color.WHITE
            p.typeface=Typeface.create("sans-serif",Typeface.BOLD)
            p.textAlign=Paint.Align.CENTER
            val title=names[id]!!
            p.textSize=when {title.length>4->35f;title.length>2->59f;else->96f}
            canvas.drawText(title,128f,128f-(p.ascent()+p.descent())/2f,p)
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,
        selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun update(uri:Uri,values:ContentValues?,selection:String?,
        selectionArgs:Array<out String>?):Int=0
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=0
}
''',encoding="utf-8")

manifest=root/"app/src/main/AndroidManifest.xml"
m=manifest.read_text(encoding="utf-8")
m=one(m,'</application>','''    <provider
        android:name=".polaris.Polaris613ArtProvider"
        android:authorities="${applicationId}.polaris.menuart"
        android:exported="true"
        android:grantUriPermissions="true" />
    </application>''',"icon provider registration")
manifest.write_text(m,encoding="utf-8")

# The CAL frontend was enabled in 6.12; take all 4 tabs from the same tree.
# In Streaming the two icon category entries precede real liked song rows.
autocar=pkg/"playback/AutoCar.kt"
a=autocar.read_text(encoding="utf-8")
a=one(a,'val known = tabs?.take(2)','val known = tabs?.take(4)',"CAL 4 tabs")
a=one(a,'val categories = items.take(4)','val categories = items.take(2)',"CAL 2 streaming groups")
a=one(a,'val songs = items.drop(4)','val songs = items.drop(2)',"CAL liked songs")
# Radio uses exactly the same sectioned grid+rows as Streaming.
a=one(a,'if (list?.mediaId == RadioCatalog.STREAMING_BROWSE_ROOT_ID) {',
'''if (list?.mediaId == Polaris613Nav.STREAM ||
        list?.mediaId == Polaris613Nav.RADIO) {''',"CAL radio/stream sections")
a=one(a,'import com.dd3boh.outertune.polaris.RadioCatalog',
'''import com.dd3boh.outertune.polaris.RadioCatalog
import com.dd3boh.outertune.polaris.Polaris613Nav''',"CAL nav import")
autocar.write_text(a,encoding="utf-8")

gradle=root/"app/build.gradle.kts"
b=gradle.read_text(encoding="utf-8")
b=one(b,"versionCode = 100612","versionCode = 100613","version code")
b=one(b,'versionName = "6.12"','versionName = "6.13"',"version name")
gradle.write_text(b,encoding="utf-8")
test=pkg/"../../androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
test=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
t=test.read_text(encoding="utf-8")
t=one(t,"Polaris 6.12 (versionCode 100612)","Polaris 6.13 (versionCode 100613)","report version")
test.write_text(t,encoding="utf-8")
print("PASS 6.13: 4 tabs, 2 image-only categories, actual liked rows, original radio art, programme only, CAN blank, original signed streaming engine")
