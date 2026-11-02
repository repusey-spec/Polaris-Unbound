package app.hush.music.polaris

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Select once per MediaSource: all HLS manifests/segments retain that source's factory. */
class PolarisSourceFactory(
    private val music: MediaSource.Factory,
    private val radio: MediaSource.Factory,
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val selected = if (PolarisSourcePolicy.isRadio(mediaItem.mediaId)) radio else music
        PolarisErrorReport.record("source-create route=${if (selected === radio) "radio-direct" else "hush-original"} ${PolarisErrorReport.item(mediaItem)}")
        return try { selected.createMediaSource(mediaItem) } catch (error: Exception) {
            PolarisErrorReport.failure("source-create", error, mediaItem)
            throw error
        }
    }
    override fun getSupportedTypes(): IntArray = (music.supportedTypes.toList() + radio.supportedTypes.toList()).distinct().toIntArray()
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        music.setDrmSessionManagerProvider(provider); radio.setDrmSessionManagerProvider(provider); return this
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        music.setLoadErrorHandlingPolicy(policy); radio.setLoadErrorHandlingPolicy(policy); return this
    }
}
