package app.hush.music.polaris

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import app.hush.music.BuildConfig
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
    // Read the installed APK's actual versionCode, never a hard-coded CI run.
    val versionCode = BuildConfig.VERSION_CODE
    val buildNumber = versionCode - 100000
    var showDiagnosticMode by remember { mutableStateOf(false) }
    var showDiagnosticLog by remember { mutableStateOf(false) }

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
        TextButton(onClick = { showDiagnosticMode = true }) {
            Text("진단 모드")
        }
    }
    // A small diagnostics hub: the record viewer is not exposed directly on
    // the Radio home screen. Do not perform diagnostics while driving.
    if (showDiagnosticMode) {
        AlertDialog(
            onDismissRequest = { showDiagnosticMode = false },
            title = { Text("Polaris 진단 모드") },
            text = {
                Column {
                    Text("현재 빌드: Build #$buildNumber")
                    Text("앱 버전 코드: $versionCode")
                    Text("진단 기록은 주차 후 확인해 주세요.")
                    TextButton(
                        onClick = {
                            showDiagnosticMode = false
                            showDiagnosticLog = true
                        },
                    ) {
                        Text("진단 기록 보기 · 주차 후 확인")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticMode = false }) { Text("닫기") }
            },
        )
    }

    if (showDiagnosticLog) {
        // Include the build in copied reports to identify exactly which APK
        // created the diagnostic events.
        val diagnosticText = remember(showDiagnosticLog) {
            PolarisRadioDiagnostics.dump(context)
        }
        val report = "Polaris Build #$buildNumber (versionCode $versionCode)\n\n$diagnosticText"
        AlertDialog(
            onDismissRequest = { showDiagnosticLog = false },
            title = { Text("AA·라디오 진단 기록") },
            text = {
                Text(
                    report,
                    modifier = Modifier.heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Polaris radio diagnostics", report))
                    showDiagnosticLog = false
                    showDiagnosticMode = true
                }) { Text("기록 복사") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiagnosticLog = false
                    showDiagnosticMode = true
                }) { Text("진단 모드") }
            },
        )
    }
}
