package app.hush.music.polaris

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Observes both routes without changing requests, credentials or cache behavior. */
class PolarisTraceDataSource(private val delegate: DataSource, private val route: String) : DataSource {
    private var request: DataSpec? = null
    private var reported = false
    override fun open(dataSpec: DataSpec): Long {
        request = dataSpec; reported = false
        PolarisErrorReport.record("load-open route=$route scheme=${dataSpec.uri.scheme ?: "id"} host=${dataSpec.uri.host ?: "none"} key=${dataSpec.key != null} position=${dataSpec.position}")
        return try {
            delegate.open(dataSpec).also { length ->
                val type = delegate.responseHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull()
                PolarisErrorReport.record("load-opened route=$route length=$length content-type=$type")
            }
        } catch (error: Exception) { failed("open", error); throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try { delegate.read(buffer, offset, length) } catch (error: Exception) { failed("read", error); throw error }
    private fun failed(stage: String, error: Exception) {
        if (reported) return
        reported = true
        PolarisErrorReport.failure("load-$stage route=$route key=${request?.key ?: "none"}", error)
    }
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
    override fun close() { try { delegate.close() } finally { request = null } }
}
