package app.hush.music.polarisv5

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.polarisunbound.app.StationArt
import java.io.File
import java.io.FileOutputStream

/** Delivers actual v0.58 station artwork to Android Auto (not a Hush fallback icon). */
class V5StationArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = context ?: throw IllegalStateException("No provider context")
        val id = uri.lastPathSegment ?: throw IllegalArgumentException("Station missing")
        require(V5Radio.valid(id)) { "Unknown station" }
        val dir = File(app.cacheDir, "polaris_v5_station_art")
        check(dir.exists() || dir.mkdirs()) { "Art cache directory unavailable" }
        val file = File(dir, id + ".png")
        if (!file.exists() || file.length() < 100) {
            FileOutputStream(file).use { out ->
                check(StationArt.bitmap(app, id, 512).compress(
                    android.graphics.Bitmap.CompressFormat.PNG, 100, out
                )) { "Station artwork encoder failed" }
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
    override fun delete(
        uri: Uri, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
