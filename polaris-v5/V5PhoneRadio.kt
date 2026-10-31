package app.hush.music.polarisv5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.hush.music.LocalPlayerConnection
import app.hush.music.ui.screens.Screens
import com.polarisunbound.app.RadioPresets

const val V5_RADIO_ROUTE = "polaris_v5_radio"

/** Text-first app-wide source tabs. No decorative note icons. */
@Composable
fun V5SourceTabs(navController: NavHostController, route: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        val onRadio = route == V5_RADIO_ROUTE
        if (onRadio) Button(onClick = {}) { Text("Radio") }
        else OutlinedButton(onClick = {
            navController.navigate(V5_RADIO_ROUTE) { launchSingleTop = true }
        }) { Text("Radio") }

        if (!onRadio) Button(onClick = {}) { Text("Streaming") }
        else OutlinedButton(onClick = {
            navController.navigate(Screens.ROUTE_HOME) { launchSingleTop = true }
        }) { Text("Streaming") }
    }
}

/** 4x4 v0.58 radio slots, with empty slots and station swap retained. */
@Composable
fun V5RadioScreen() {
    val context = LocalContext.current
    val connection = LocalPlayerConnection.current
    var revision by remember { mutableIntStateOf(0) }
    val grid = remember(revision) { RadioPresets.layoutIds(context).toList() }
    var editSlot by remember { mutableIntStateOf(-1) }
    var lastError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        lastError?.let { Text("Radio: " + it) }
        Text("Polaris Radio  ·  v5.0")
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().height(340.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(grid) { slot, id ->
                OutlinedButton(
                    onClick = {
                        if (id == null) {
                            editSlot = slot
                        } else {
                            val player = connection?.player
                            if (player == null) {
                                lastError = "재생 서비스 연결 전입니다"
                            } else {
                                val ordered = V5Radio.queue(context)
                                val index = ordered.indexOfFirst {
                                    it.mediaId == V5Radio.STATION_PREFIX + id
                                }
                                if (index >= 0) {
                                    player.setMediaItems(ordered, index, 0L)
                                    player.prepare()
                                    player.play()
                                    lastError = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(68.dp),
                    contentPadding = PaddingValues(3.dp),
                ) {
                    Text(
                        if (id == null) "+" else RadioPresets.stationName(id)
                            .replace(" MusicFM","").replace(" CoolFM","")
                            .replace(" New York"," NY"),
                        maxLines = 3,
                    )
                }
            }
        }
        Text("방송국 배치 변경: 빈 칸의 +를 눌러 방송국을 이동합니다.")
        Text("핸들 이전·다음 버튼은 실제 방송국 13개의 Media3 재생 대기열을 이동합니다.")
    }

    if (editSlot >= 0) {
        AlertDialog(
            onDismissRequest = { editSlot = -1 },
            title = { Text("방송국 위치 선택") },
            text = {
                Column {
                    RadioPresets.IDS.forEachIndexed { idx, id ->
                        TextButton(onClick = {
                            RadioPresets.swap(context, editSlot, idx)
                            revision++
                            editSlot = -1
                        }) {
                            Text(RadioPresets.stationName(id))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editSlot = -1 }) { Text("닫기") } },
        )
    }
}
