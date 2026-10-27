package app.hush.music.polaris

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 0.58 schedule/clock contract for Android Auto.
 * Only update media metadata; preserve the live stream URL and player state.
 */
class PolarisRadioMetadataRefresh(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) {
    fun start() {
        PolarisRadioMetadata.install(context)
        scope.launch {
            var lastStation: String? = null
            var nextRefresh = 0L
            while (isActive) {
                val item = player.currentMediaItem
                val station = item?.mediaId?.takeIf { it.startsWith("polaris:station:") }
                    ?.removePrefix("polaris:station:")
                    ?.takeIf { RadioCatalog.find(it) != null }
                if (station == null) {
                    lastStation = null
                    nextRefresh = 0L
                } else {
                    if (station != lastStation) {
                        lastStation = station
                        nextRefresh = 0L
                    }
                    if (SystemClock.elapsedRealtime() >= nextRefresh) {
                        val resolved = withContext(Dispatchers.IO) {
                            if (station == "kr5") null else
                                runCatching { CurrentProgramResolver.resolve(station) }.getOrNull()
                        }
                        if (resolved != null) {
                            CurrentProgramResolver.save(context, station, resolved)
                            val interval = CurrentProgramResolver.nextRefreshDelay(station, resolved)
                                .coerceIn(15_000L, 300_000L)
                            nextRefresh = SystemClock.elapsedRealtime() + interval
                        } else {
                            nextRefresh = SystemClock.elapsedRealtime() + 90_000L
                        }
                    }
                    val liveItem = player.currentMediaItem
                    val title = PolarisRadioMetadata.title(station)
                    if (liveItem?.mediaId == "polaris:station:$station" &&
                        liveItem.mediaMetadata.title?.toString() != title &&
                        player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                        val index = player.currentMediaItemIndex
                        if (index in 0 until player.mediaItemCount) {
                            runCatching {
                                player.replaceMediaItem(
                                    index,
                                    liveItem.buildUpon()
                                        .setMediaMetadata(PolarisRadioMetadata.mediaMetadata(station))
                                        .build(),
                                )
                            }.onFailure {
                                PolarisRadioDiagnostics.record(context, "AA metadata failed=" + it.javaClass.simpleName)
                            }
                        }
                    }
                }
                delay(5_000L)
            }
        }
    }
}
