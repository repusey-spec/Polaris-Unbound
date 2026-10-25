package app.hush.music.polaris

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.navigation.NavHostController
import app.hush.music.LocalPlayerConnection
import app.hush.music.ui.screens.Screens

@Composable
fun PolarisRadioScreen(navController: NavHostController) {
    val context = LocalContext.current
    val connection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var radioError by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {}) { Text("Radio") }
            Button(onClick = { navController.navigate(Screens.ROUTE_HOME) }) { Text("Streaming") }
        }
        radioError?.let { Text(it) }
        PolarisRadioGrid(
            layout = PolarisRadioLayout(context),
            onPlayStation = { id ->
                scope.launch {
                    radioError = null
                    val url = try {
                        withContext(Dispatchers.IO) {
                            if (PolarisRadioResolver.needsResolution(id)) {
                                PolarisRadioResolver.resolve(id)
                            } else {
                                PolarisRadioStreams.streams.firstOrNull { it.id == id }?.url
                                    ?: error("No stream URL for $id")
                            }
                        }
                    } catch (e: Exception) {
                        radioError = "라디오 주소 확인 실패: ${e.message}"
                        return@launch
                    }
                    val station = RadioCatalog.find(id)
                    val item = MediaItem.Builder()
                        .setMediaId("polaris:station:$id")
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(station?.title ?: id)
                                .setArtist("Polaris Radio")
                                .build()
                        )
                        .build()
                    val player = connection?.player
                    if (player == null) {
                        radioError = "재생 서비스에 연결되지 않았습니다."
                    } else {
                        player.setMediaItem(item)
                        player.prepare()
                        player.play()
                    }
                }
            }p.hush.music.polaris

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.navigation.NavHostController
import app.hush.music.LocalPlayerConnection
import app.hush.music.ui.screens.Screens

@Composable
fun PolarisRadioScreen(navController: NavHostController) {
    val context = LocalContext.current
    val connection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var radioError by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {}) { Text("Radio") }
            Button(onClick = { navController.navigate(Screens.ROUTE_HOME) }) { Text("Streaming") }
        }
        radioError?.let { Text(it) }
        PolarisRadioGrid(
            layout = PolarisRadioLayout(context),
            onPlayStation = { id ->
                val item = MediaItem.Builder().setMediaId("polaris:station:$id").build()
                connection?.player?.apply {
                    setMediaItem(item)
                    prepare()
                    play()
                }
            },
        )
    }
}
