package app.hush.music.polaris

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.hush.music.playback.PlayerConnection
import app.hush.music.ui.component.hushMarquee
import app.hush.music.ui.player.MiniPlayerContentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Radio titles are keyed by the stable Polaris station media ID, not by
 * Hush's YouTube/database-backed MediaMetadata (which can be null for radio).
 * The observable radio ID also works when playback starts from Android Auto.
 */
private fun radioStationId(item: MediaItem?): String? {
    val mediaId = item?.mediaId ?: return null
    if (!mediaId.startsWith("polaris:station:")) return null
    return mediaId.removePrefix("polaris:station:")
        .takeIf { RadioCatalog.find(it) != null }
}

@Composable
fun rememberPolarisRadioStationId(connection: PlayerConnection): String? {
    val player = connection.player
    var stationId by remember(player) { mutableStateOf(radioStationId(player.currentMediaItem)) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                stationId = radioStationId(mediaItem)
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                stationId = radioStationId(player.currentMediaItem)
            }
        }
        player.addListener(listener)
        stationId = radioStationId(player.currentMediaItem)
        onDispose { player.removeListener(listener) }
    }
    return stationId
}

/**
 * First line: live programme (or LIVE when the feed has no programme).
 * Second line: the station's human-readable name.
 * Uses existing Polaris cache and original programme-boundary refresh rules.
 */
@Composable
fun RowScope.PolarisRadioNowPlaying(
    stationId: String,
    colors: MiniPlayerContentColors,
) {
    // A restored dormant playlist entry must not advertise 89.1 as the
    // current station until real radio playback has actually happened.
    if (!PolarisRadioUiSession.hasStartedRadio) {
        Spacer(modifier = Modifier.weight(1f))
        return
    }
    val context = LocalContext.current
    val station = RadioCatalog.find(stationId)?.title ?: CurrentProgramResolver.stationName(stationId)
    var programme by remember(stationId) { mutableStateOf("LIVE") }

    LaunchedEffect(stationId) {
        val cached = CurrentProgramResolver.cached(context, stationId)
            ?.takeIf { CurrentProgramResolver.isCurrentNow(stationId, it) }
        programme = cached?.program?.takeIf { it.isNotBlank() && it != station } ?: "LIVE"

        while (true) {
            val result = withContext(Dispatchers.IO) {
                runCatching { CurrentProgramResolver.resolve(stationId) }.getOrNull()
            }
            if (result != null) {
                CurrentProgramResolver.save(context, stationId, result)
                programme = result.program?.takeIf { it.isNotBlank() && it != station } ?: "LIVE"
            } else if (cached == null) {
                programme = "LIVE"
            }
            delay(CurrentProgramResolver.nextRefreshDelay(stationId, result ?: cached))
        }
    }

    Column(
        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
    ) {
        Text(
            text = programme,
            style = MaterialTheme.typography.titleMedium,
            color = colors.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.hushMarquee(),
        )
        Text(
            text = station,
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.hushMarquee(),
        )
    }
}
