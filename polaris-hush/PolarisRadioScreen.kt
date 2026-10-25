package app.hush.music.polaris

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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
    var selectedRadioId by remember { mutableStateOf<String?>(null) }
    var nowPlaying by remember { mutableStateOf("") }
    LaunchedEffect(selectedRadioId) {
        val id = selectedRadioId ?: return@LaunchedEffect
        while (true) {
            val cached = CurrentProgramResolver.cached(context, id)
            if (cached != null) nowPlaying = cached.phoneText(id)
            val result = try {
                withContext(Dispatchers.IO) { CurrentProgramResolver.resolve(id) }
            } catch (_: Exception) { null }
            if (result != null) {
                CurrentProgramResolver.save(context, id, result)
                nowPlaying = result.phoneText(id)
            } else if (cached == null) {
                nowPlaying = CurrentProgramResolver.stationName(id)
            }
            delay(CurrentProgramResolver.nextRefreshDelay(id, result ?: cached))
        }
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        radioError?.let { Text(it) }
        if (selectedRadioId != null) Text(nowPlaying)
        PolarisRadioGrid(
            layout = PolarisRadioLayout(context),
            onPlayStation = { id ->
                scope.launch {
                    radioError = null
                    selectedRadioId = id
                    nowPlaying = CurrentProgramResolver.stationName(id)
                    val url = try {
                        withContext(Dispatchers.IO) {
                            PolarisRadioPlayback.resolveUrl(id)
                        }
                    } catch (e: Exception) {
                        radioError = "라디오 주소 확인 실패: ${e.message}"
                        return@launch
                    }
                    val item = PolarisRadioPlayback.mediaItem(id, url)
                    val player = connection?.player
                    if (player == null) radioError = "재생 서비스에 연결되지 않았습니다."
                    else {
                        player.setMediaItem(item)
                        player.prepare()
                        player.play()
                    }
                }
            },
        )
    }
}
