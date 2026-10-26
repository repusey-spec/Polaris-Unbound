package app.hush.music.polaris

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.hush.music.LocalPlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keep the Radio screen focused on station selection and playback.
 * Currently playing programme metadata is rendered in Hush's shared mini player,
 * so it remains visible on the Radio tab and all Streaming subpages.
 */
@Composable
fun PolarisRadioScreen(navController: NavHostController) {
    val context = LocalContext.current
    val connection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var radioError by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 12.dp).statusBarsPadding().padding(top = 64.dp, bottom = 8.dp)) {
        radioError?.let { Text(it) }
        PolarisRadioGrid(
            layout = PolarisRadioLayout(context),
            onPlayStation = { id ->
                scope.launch {
                    radioError = null
                    val url = try {
                        withContext(Dispatchers.IO) { PolarisRadioPlayback.resolveUrl(id) }
                    } catch (e: Exception) {
                        radioError = "라디오 주소 확인 실패: ${e.message}"
                        return@launch
                    }
                    val item = PolarisRadioPlayback.mediaItem(id, url)
                    val player = connection?.player
                    if (player == null) {
                        radioError = "재생 서비스에 연결되지 않았습니다."
                    } else {
                        PolarisRadioNetwork.radioActive = true
                        player.setMediaItem(item)
                        player.prepare()
                        player.play()
                    }
                }
            },
        )
        TextButton(onClick = { showDiagnostics = true }) {
            Text("진단 기록 보기 · 주차 후 확인")
        }
    }
    if (showDiagnostics) {
        val diagnosticText = remember(showDiagnostics) { PolarisRadioDiagnostics.dump(context) }
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("AA·라디오 진단 기록") },
            text = {
                Text(
                    diagnosticText,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Polaris radio diagnostics", diagnosticText))
                    showDiagnostics = false
                }) { Text("기록 복사") }
            },
            dismissButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("닫기") }
            },
        )
    }
}
