package app.hush.music.polaris

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Polaris v0.58 radio uses an ordinary, direct HTTP DataSource with Media3's
 * in-source HLS retries, not Hush's YouTube ID resolver or song byte cache.
 * Media3 may open segment URLs on a loading thread: only volatile/atomic state
 * is read on that thread, never a Player property.
 */
object PolarisRadioNetwork {
    @Volatile var radioActive: Boolean = false

    fun isRadioRequest(spec: DataSpec): Boolean {
        val protocol = spec.uri.scheme?.lowercase(java.util.Locale.US)
        return radioActive && (protocol == "http" || protocol == "https")
    }
}

class PolarisRadioRoutingDataSource(
    private val songFactory: DataSource.Factory,
    private val radioFactory: DataSource.Factory,
) : DataSource {
    private var actual: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        check(actual == null) { "DataSource already open" }
        val delegate =
            (if (PolarisRadioNetwork.isRadioRequest(dataSpec)) radioFactory else songFactory)
                .createDataSource()
        listeners.forEach(delegate::addTransferListener)
        actual = delegate
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(actual).read(buffer, offset, length)

    override fun getUri(): Uri? = actual?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        actual?.responseHeaders ?: emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        actual?.addTransferListener(transferListener)
    }

    override fun close() {
        try {
            actual?.close()
        } finally {
            actual = null
        }
    }
}
