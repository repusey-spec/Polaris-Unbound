package app.hush.music.polaris

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.hush.music.R
import app.hush.music.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Dedicated live-radio now-playing view. Hush's ordinary music player remains
 * unchanged for non-radio media. Reuse the exact station tile artwork from the
 * 4x4 preset grid; do not fall through to the null YouTube-song metadata view.
 */
@Composable
fun PolarisRadioFullPlayer(connection: PlayerConnection, stationId: String) {
    val context = LocalContext.current
    val station = RadioCatalog.find(stationId)
    val stationTitle = station?.title ?: CurrentProgramResolver.stationName(stationId)
    val isPlaying by connection.isPlaying.collectAsStateWithLifecycle()
    val playbackState by connection.playbackState.collectAsStateWithLifecycle()
    var program by remember(stationId) { mutableStateOf("LIVE RADIO") }
    val bitmap = remember(stationId) {
        StationArt.bitmap(context, stationId, 1024).asImageBitmap()
    }

    LaunchedEffect(stationId) {
        val cached = CurrentProgramResolver.cached(context, stationId)
            ?.takeIf { CurrentProgramResolver.isCurrentNow(stationId, it) }
        program = cached?.program?.takeIf { it.isNotBlank() && it != stationTitle }
            ?: "LIVE RADIO"
        while (true) {
            val entry = withContext(Dispatchers.IO) {
                runCatching { CurrentProgramResolver.resolve(stationId) }.getOrNull()
            }
            if (entry != null) {
                CurrentProgramResolver.save(context, stationId, entry)
                program = entry.program?.takeIf { it.isNotBlank() && it != stationTitle }
                    ?: "LIVE RADIO"
            }
            delay(CurrentProgramResolver.nextRefreshDelay(stationId, entry ?: cached))
        }
    }

    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.surfaceContainerHigh, colors.surface, colors.surfaceContainer),
                ),
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val coverSize = minOf(maxWidth - 64.dp, maxHeight * 0.43f, 400.dp)
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "POLARIS  ·  LIVE RADIO",
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))
            Image(
                bitmap = bitmap,
                contentDescription = stationTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(coverSize)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stationTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = program,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(28.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = connection::seekToPrevious,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "이전 라디오 채널",
                        modifier = Modifier.size(34.dp),
                        tint = colors.onSurface,
                    )
                }
                FilledIconButton(
                    onClick = {
                        if (playbackState == Player.STATE_ENDED) {
                            connection.player.seekToDefaultPosition()
                            connection.player.prepare()
                            connection.player.play()
                        } else if (isPlaying) {
                            connection.player.pause()
                        } else {
                            connection.player.play()
                        }
                    },
                    modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play,
                        ),
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(
                    onClick = connection::seekToNext,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "다음 라디오 채널",
                        modifier = Modifier.size(34.dp),
                        tint = colors.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
