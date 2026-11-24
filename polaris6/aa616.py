#!/usr/bin/env python3
"""Polaris 6.16: real image-only AA category tiles and compact Home rows."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
pkg=root/"app/src/main/java/com/dd3boh/outertune"
cb=pkg/"playback/MediaLibrarySessionCallback.kt"
nav=pkg/"polaris/Polaris613Nav.kt"
auto=pkg/"playback/AutoCar.kt"
def one(path,old,new,why):
    s=path.read_text(encoding="utf-8")
    count=s.count(old)
    assert count==1, f"6.16 {why}: expected one, got {count}"
    path.write_text(s.replace(old,new,1),encoding="utf-8")
one(nav,
'''            .putString("artist",item.mediaMetadata.artist?.toString()?.take(160).orEmpty())
            .apply()''',
'''            .putString("artist",item.mediaMetadata.artist?.toString()?.take(160).orEmpty())
            .putString("artwork_uri",item.mediaMetadata.artworkUri?.toString().orEmpty())
            .apply()''',"store artwork alongside last played music")
one(nav,
'''        val artist=p.getString("artist","").orEmpty()
        val item=MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setArtist(artist).setSubtitle(artist)
                .setIsPlayable(true).setIsBrowsable(false).build())''',
'''        val artist=p.getString("artist","").orEmpty()
        val artwork=p.getString("artwork_uri","").orEmpty()
            .takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
        val item=MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setArtist(artist).setSubtitle(artist)
                .setArtworkUri(artwork)
                .setIsPlayable(true).setIsBrowsable(false).build())''',"restore saved album artwork")
one(cb,
'''                        .setTitle("최근 라디오 · " + station.title)
                        .setSubtitle(null).setArtist(null).build()''',
'''                        .setTitle(("최근 라디오 · " + station.title)
                            .replace(10.toChar(),' ').replace(13.toChar(),' ').take(70))
                        .setSubtitle(null).setArtist(null).build()''',"radio single-line Home")
one(cb,
'''                    .setTitle("최근 음악 · " +
                        item.mediaMetadata.title?.toString().orEmpty())
                    .build()''',
'''                    .setTitle(("최근 음악 · " +
                        item.mediaMetadata.title?.toString().orEmpty())
                        .replace(10.toChar(),' ').replace(13.toChar(),' ').take(70))
                    .setSubtitle(null).setArtist(null).build()''',"music title one line, no artist subtitle")

# Native Car App Library renderer: use packaged resources for the four image
# categories, not content:// URLs whose bytes/host caches may be stale.
one(auto,
'''    val (picture, cover) = carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note)
    val tile = GridItem.Builder().setTitle(item.name()).setImage(picture, if (cover) GridItem.IMAGE_TYPE_LARGE else GridItem.IMAGE_TYPE_ICON)''',
'''    val categoryArt = when(item.mediaId) {
        Polaris613Nav.KR -> R.drawable.polaris616_kr
        Polaris613Nav.WORLD -> R.drawable.polaris616_world
        RadioCatalog.PLAYLIST_ROOT_ID -> R.drawable.polaris616_playlist
        Polaris613Nav.DOWNLOADS -> R.drawable.polaris616_download
        else -> null
    }
    val (picture, cover) = if(categoryArt != null)
        carContext.icon(categoryArt) to true
    else carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note)
    val tile = GridItem.Builder().setTitle(item.name()).setImage(picture, if (cover) GridItem.IMAGE_TYPE_LARGE else GridItem.IMAGE_TYPE_ICON)''',
"native big artwork for category grids")
one(auto,
'''    item.under()?.let(row::addText)
    if (item.mediaMetadata.isBrowsable == true) row.setBrowsable(true)''',
'''    // Home recents must be logo/cover plus exactly one text line.
    if (!item.mediaMetadata.title?.toString().orEmpty().startsWith("최근 라디오 ·") &&
        !item.mediaMetadata.title?.toString().orEmpty().startsWith("최근 음악 ·")) {
        item.under()?.let(row::addText)
    }
    if (item.mediaMetadata.isBrowsable == true) row.setBrowsable(true)''',
"Home one-line row renderer")

# Cache-busting for Media3 browser fallback, where Car App Library resources
# are unavailable. Path segment stays stable; query differs per version.
one(nav,
'''        .appendPath(id).build()''',
'''        .appendPath(id).appendQueryParameter("v","616").build()''',
"new artwork URI for Media3 fallback")

# Four EXACT approved, monochrome square PNGs, extracted from the user
# chosen final designs. Do not redraw text, gradients or purple placeholders.
from shutil import copyfile
draw=root/"app/src/main/res/drawable-nodpi"; draw.mkdir(parents=True,exist_ok=True)
for name in ("kr","world","playlist","download"):
    source=Path(__file__).with_name("art616_"+name+".png")
    assert source.is_file(), "Missing approved art: "+str(source)
    raw=source.read_bytes()
    assert raw.startswith(b"\x89PNG\r\n\x1a\n") and len(raw)>500
    copyfile(source,draw/("polaris616_"+name+".png"))

# Media-browser fallback MUST deliver the same artwork as native CAL.
provider=pkg/"polaris/Polaris613ArtProvider.kt"
one(provider,
'''            if(id=="kr" || id=="world") {
                val res=if(id=="kr") R.drawable.polaris615_kr
                    else R.drawable.polaris615_world''',
'''            if(id=="kr" || id=="world" || id=="playlists" || id=="downloads") {
                val res=when(id) {
                    "kr" -> R.drawable.polaris616_kr
                    "world" -> R.drawable.polaris616_world
                    "playlists" -> R.drawable.polaris616_playlist
                    else -> R.drawable.polaris616_download
                }''', "four fallback arts match native resources")
one(provider,'File(ctx.cacheDir,"polaris-615-icons")',
    'File(ctx.cacheDir,"polaris-616-icons")',"invalidate old raster icon cache")

print("PASS: 6.16 monochrome user-approved four PNG assets in native and browser paths")
print("PASS: existing Home + 6.15 Radio recent and music playback unchanged")
