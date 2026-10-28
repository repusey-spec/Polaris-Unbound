package app.hush.music.polaris

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.hush.music.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/**
 * Android Auto accesses artwork from a separate process. Publish each radio
 * station's existing StationArt bitmap through a stable read-only content URI,
 * rather than requiring a large bitmap to cross the MediaSession binder.
 */
object PolarisRadioArtwork {
    private const val SUFFIX = ".polaris.radioart"

    fun uri(stationId: String): Uri {
        require(RadioCatalog.find(stationId) != null) { "Unknown radio station" }
        return Uri.Builder()
            .scheme("content")
            .authority(BuildConfig.APPLICATION_ID + SUFFIX)
            .appendPath("station")
            .appendPath(stationId)
            .build()
    }
}

class PolarisRadioArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun validate(uri: Uri): String {
        if (uri.scheme != "content" ||
            uri.authority != BuildConfig.APPLICATION_ID + ".polaris.radioart" ||
            uri.pathSegments.size != 2 ||
            uri.pathSegments[0] != "station"
        ) throw FileNotFoundException("Unsupported radio artwork URI")
        return uri.pathSegments[1].takeIf { RadioCatalog.find(it) != null }
            ?: throw FileNotFoundException("Unknown radio artwork")
    }

    override fun getType(uri: Uri): String? =
        if (runCatching { validate(uri) }.isSuccess) "image/png" else null

    @Synchronized
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Radio artwork is read-only")
        val stationId = validate(uri)
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val dir = File(ctx.cacheDir, "polaris-radio-artwork")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw FileNotFoundException("Unable to create artwork directory")
        }
        val file = File(dir, "$"+"{stationId}.png")
        if (!file.isFile || file.length() == 0L) {
            val temp = File(dir, "$"+"{stationId}.tmp")
            try {
                temp.outputStream().use { out ->
                    if (!StationArt.bitmap(ctx, stationId, 256)
                            .compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw FileNotFoundException("Unable to encode station artwork")
                    }
                }
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                }
            } finally {
                temp.delete()
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
