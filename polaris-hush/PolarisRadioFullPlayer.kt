package app.hush.music.polaris

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hush.music.R
import app.hush.music.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-size LIVE radio player, only when Media3 currentMediaItem is polaris:station:...
 * Hush's existing full-song player stays completely untouched for Streaming.
 *
 * Radio station artwork is a 512 px rendering of the exact same station tile used
 * on the radio page. There is deliberately no song progress slider on live radio.
 */
@Composable
fun PolarisRadioFullPlayer(
    stationId: String,
    playerConnection: PlayerConnection,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val station = RadioCatalog.find(stationId)
    val stationName = station?.title ?: CurrentProgramResolver.stationName(stationId)
    val stationArt = remember(stationId) {
        StationArt.bitmap(context, stationId, 512).asImageBitmap()
    }
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    var programme by remember(stationId) { mutableStateOf("LIVE RADIO") }

    LaunchedEffect(stationId) {
        val cached = CurrentProgramResolver.cached(context, stationId)
            ?.takeIf { CurrentProgramResolver.isCurrentNow(stationId, it) }
        programme = cached?.program?.takeIf { !it.isNullOrBlank() && it != stationName } ?: "LIVE RADIO"
        while (true) {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { CurrentProgramResolver.resolve(stationId) }.getOrNull()
            }
            if (resolved != null) {
                CurrentProgramResolver.save(context, stationId, resolved)
                programme = resolved.program?.takeIf { !it.isNullOrBlank() && it != stationName } ?: "LIVE RADIO"
            }
            delay(CurrentProgramResolver.nextRefreshDelay(stationId, resolved ?: cached))
        }
    }

    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Polaris Radio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "●  LIVE",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
                TextButton(onClick = onClose) { Text("닫기") }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.surfaceContainer,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 332.dp).fillMaxWidth().aspectRatio(1f),
            ) {
                Image(
                    bitmap = stationArt,
                    contentDescription = stationName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(20.dp)),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = programme,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stationName,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier.size(70.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHighest),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    onClick = { playerConnection.seekToPrevious() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "이전 라디오 채널",
                        tint = colors.onSurface,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Button(
                    modifier = Modifier.size(82.dp),
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    onClick = {
                        if (isPlaying) playerConnection.player.pause() else playerConnection.player.play()
                    },
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play,
                        ),
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(36.dp),
                    )
                }
                Button(
                    modifier = Modifier.size(70.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHighest),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    onClick = { playerConnection.seekToNext() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "다음 라디오 채널",
                        tint = colors.onSurface,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (playbackState == androidx.media3.common.Player.STATE_BUFFERING)
                    "라이브 방송 연결 중" else "실시간 라디오 · 채널 변경은 이전/다음 버튼",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
