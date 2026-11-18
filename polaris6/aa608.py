#!/usr/bin/env python3
"""Polaris 6.08: InterTune-style AA streaming home and center-drawn radio tiles.
Apply after 6.07. Media player, radio URLs, databases and voice routing unchanged.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
cb=pkg/"playback/MediaLibrarySessionCallback.kt"
s=cb.read_text(encoding="utf-8")

def once(text,old,new,label):
    n=text.count(old)
    assert n==1, f"{label}: {n} matches"
    return text.replace(old,new,1)

s=once(s,
    "                RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()",
    "                RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)",
    "streaming root")

old="""    /** Original Polaris 0.58 live programme and local clock under each radio tile. */
    private fun polarisRadioTile(id: String): MediaItem {
        val item = PolarisRadioRequests.queueItem(id)
        val title = PolarisRadioMetadata.title(id)
        return item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon().setTitle(title).build()
        ).build()
    }
"""
new="""    /**
     * Android Auto's template center-aligns the artwork but left-aligns the
     * metadata text. There is no supported label-gravity property. Draw the
     * live programme and station centered within a BROWSE-ONLY tile instead.
     */
    private fun polarisRadioTile(id: String): MediaItem {
        val item = PolarisRadioRequests.queueItem(id)
        val station = RadioCatalog.find(id) ?: return item
        val live = PolarisRadioMetadata.title(id)
        return item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon()
                .setTitle("\\u200B")
                .setSubtitle(null)
                .setArtist(null)
                .setArtworkUri(PolarisRadioArtwork.browseTileUri(id, station.title, live))
                .build()
        ).build()
    }
"""
s=once(s,old,new,"browse radio title")

anchor="    /**\n     * A browser asking to be told when a list changes."
assert s.count(anchor)==1
home="""    /**
     * The unmodified InterTune AA landing shows 4 category icons and then a
     * browsable, playable list of songs, not a playlists-only intermediate page.
     * Keep the separate Polaris radio tab and the complete playlist folder.
     */
    private suspend fun polarisStreamingHome(page: Int, pageSize: Int): List<MediaItem> {
        val category = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
        val icons = listOf(
            browsableMediaItem(MusicService.SONG, context.getString(R.string.songs), null,
                drawableUri(R.drawable.music_note), MediaMetadata.MEDIA_TYPE_PLAYLIST,
                style(single = category)),
            browsableMediaItem(MusicService.ARTIST, context.getString(R.string.artists), null,
                drawableUri(R.drawable.artist), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                style(single = category)),
            browsableMediaItem(MusicService.ALBUM, context.getString(R.string.albums), null,
                drawableUri(R.drawable.album), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                style(single = category)),
            browsableMediaItem(MusicService.PLAYLIST, context.getString(R.string.playlists), null,
                drawableUri(R.drawable.queue_music), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                style(single = category)),
        )
        val library = librarySongs()
        val rows = if (library.isNotEmpty()) {
            // This path is resolved by the existing PlayRequest.Song -> librarySongs().
            library.map { it.toMediaItem(MusicService.SONG) }
        } else {
            // Remote likes are playable through PlayRequest.Playlist, not Song.
            carLikedSongs().take(AutoBrowse.MOST_SONGS).map {
                it.toMediaItem(MusicService.PLAYLIST + "/" + PlaylistEntity.LIKED_PLAYLIST_ID)
            }
        }
        return CarSearch.page(icons + rows, page, pageSize)
    }

"""
s=s.replace(anchor,home+anchor,1)
s=once(s,
    """                if(id == RadioCatalog.RADIO_ROOT_ID) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, GRID)
""",
    """                if (id == RadioCatalog.RADIO_ROOT_ID) {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, GRID)
                } else if (id == RadioCatalog.STREAMING_ROOT_ID) {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)
                }
""",
    "streaming root grid/list style")
cb.write_text(s,encoding="utf-8")

art=pkg/"polaris/PolarisRadioArtworkProvider.kt"
a=art.read_text(encoding="utf-8")
a=once(a,
    '    private const val SUFFIX = ".polaris.radioart"\n',
    '    private const val SUFFIX = ".polaris.radioart"\n'
    '\n    /** A programme or time change alters this URI and invalidates the AA image. */\n'
    '    fun browseTileUri(id: String, station: String, live: String): Uri {\n'
    '        require(RadioCatalog.find(id) != null)\n'
    '        val version = (station + ":" + live).hashCode().toUInt().toString(16)\n'
    '        return Uri.Builder().scheme("content")\n'
    '            .authority(BuildConfig.APPLICATION_ID + SUFFIX)\n'
    '            .appendPath("tile").appendPath(id).appendPath(version).build()\n'
    '    }\n',
    "browse artwork URI")
a=once(a,
    """    override fun getType(uri: Uri): String? =
        if (runCatching { validate(uri) }.isSuccess) "image/png" else null
""",
    """    private fun tileId(uri: Uri): String? {
        if (uri.scheme != "content" ||
            uri.authority != BuildConfig.APPLICATION_ID + ".polaris.radioart" ||
            uri.pathSegments.size != 3 || uri.pathSegments[0] != "tile" ||
            !uri.pathSegments[2].matches(Regex("[0-9a-f]{1,8}"))) return null
        return uri.pathSegments[1].takeIf { RadioCatalog.find(it) != null }
    }

    override fun getType(uri: Uri): String? =
        if (tileId(uri) != null || runCatching { validate(uri) }.isSuccess) "image/png" else null
""",
    "tile provider content type")
a=once(a,
    """        val stationId = validate(uri)
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
""",
    """        val tile = tileId(uri)
        val stationId = tile ?: validate(uri)
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
""",
    "tile provider validate")
a=once(a,
    """        val file = File(dir, stationId + ".png")
        if (!file.isFile || file.length() == 0L) {
""",
    """        val file = if (tile == null) File(dir, stationId + ".png")
            else File(dir, "browse-" + stationId + "-" + uri.pathSegments[2] + ".png")
        if (!file.isFile || file.length() == 0L) {
""",
    "tile provider cache")
a=once(a,
    """                    if (!StationArt.bitmap(ctx, stationId, 256)
                            .compress(Bitmap.CompressFormat.PNG, 100, out)) {
""",
    """                    val bitmap = if (tile != null) PolarisRadioBrowseTile.render(ctx, stationId)
                        else StationArt.bitmap(ctx, stationId, 256)
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
""",
    "tile provider generation")
art.write_text(a,encoding="utf-8")

tile=pkg/"polaris/PolarisRadioBrowseTile.kt"
assert not tile.exists()
tile.write_text("""package com.dd3boh.outertune.polaris

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * All captions are physically centered on the same x=256 axis as the logo,
 * rather than relying on Android Auto's uncustomizable grid label gravity.
 * This artwork is used for browsing only; radio session metadata is unchanged.
 */
internal object PolarisRadioBrowseTile {
    private const val SIZE = 512
    fun render(context: Context, id: String): Bitmap {
        val station = RadioCatalog.find(id) ?: error("Unknown radio station " + id)
        val programme = PolarisRadioLabels.of(station.title, PolarisRadioMetadata.title(id)).title
        val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(52, 55, 59))
        val logo = StationArt.bitmap(context, id, 400)
        canvas.drawBitmap(logo, null, Rect(62, 2, 450, 390),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL)
        }
        val lineWidth = 488
        val lines = StaticLayout.Builder.obtain(
            programme, 0, programme.length, titlePaint, lineWidth
        ).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).build()
        canvas.save()
        canvas.translate((SIZE - lineWidth) / 2f, 392f)
        lines.draw(canvas)
        canvas.restore()

        val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 236, 240)
            textSize = 27f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.NORMAL)
        }
        val caption = TextUtils.ellipsize(station.title,
            TextPaint(stationPaint), 488f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(caption, SIZE / 2f, 501f, stationPaint)
        return result
    }
}
""",encoding="utf-8")

g=root/"app/build.gradle.kts"
v=g.read_text(encoding="utf-8")
v=once(v,"versionCode = 100607","versionCode = 100608","versionCode")
v=once(v,'versionName = "6.07"','versionName = "6.08"',"versionName")
g.write_text(v,encoding="utf-8")
report=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
t=report.read_text(encoding="utf-8")
t=once(t,"Polaris 6.07 (versionCode 100607)","Polaris 6.08 (versionCode 100608)","report")
report.write_text(t,encoding="utf-8")

# 6.06's playlists-only assertion is intentionally superseded by 6.08.
test=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisAA606Test.kt"
t=test.read_text(encoding="utf-8")
t=once(t,
    'assertTrue(callback.contains("RadioCatalog.STREAMING_ROOT_ID -> polarisPlaylistRows()"))',
    'assertTrue(callback.contains("RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)"))',
    "old playlists-only test")
test.write_text(t,encoding="utf-8")

test608=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisAA608Test.kt"
test608.write_text("""package com.dd3boh.outertune.polaris

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class PolarisAA608Test {
    private val dir = "src/main/java/com/dd3boh/outertune/"
    private val callback = File(dir + "playback/MediaLibrarySessionCallback.kt").readText()
    private val provider = File(dir + "polaris/PolarisRadioArtworkProvider.kt").readText()
    private val tile = File(dir + "polaris/PolarisRadioBrowseTile.kt").readText()

    @Test fun aaStreamingHomeIsCategoriesAndSongsTogether() {
        assertTrue(callback.contains("RadioCatalog.STREAMING_ROOT_ID -> polarisStreamingHome(page, pageSize)"))
        assertTrue(callback.contains("MusicService.SONG, context.getString(R.string.songs)"))
        assertTrue(callback.contains("MusicService.ARTIST, context.getString(R.string.artists)"))
        assertTrue(callback.contains("MusicService.ALBUM, context.getString(R.string.albums)"))
        assertTrue(callback.contains("MusicService.PLAYLIST, context.getString(R.string.playlists)"))
        assertTrue(callback.contains("CarSearch.page(icons + rows, page, pageSize)"))
        assertTrue(callback.contains("it.toMediaItem(MusicService.SONG)"))
        assertTrue(callback.contains("style(single = category)"))
    }
    @Test fun radioArtworkCentersLiveTextWhilePlaybackArtIsUntouched() {
        assertTrue(callback.contains("PolarisRadioArtwork.browseTileUri(id, station.title, live)"))
        assertTrue(callback.contains("PolarisRadioMetadata.title(id)"))
        assertTrue(tile.contains("Layout.Alignment.ALIGN_CENTER"))
        assertTrue(tile.contains("Paint.Align.CENTER"))
        assertTrue(tile.contains("setMaxLines(2)"))
        assertTrue(provider.contains("uri.pathSegments.size != 3"))
        assertTrue(provider.contains("PolarisRadioBrowseTile.render(ctx, stationId)"))
        assertFalse(callback.contains("setTitle(live).build()"))
    }
}
""",encoding="utf-8")
print("PASS: 608 InterTune AA landing + centered live radio browse artwork; 607 media playback unchanged")
