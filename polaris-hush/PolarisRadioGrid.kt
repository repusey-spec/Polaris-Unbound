package app.hush.music.polaris

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Polaris phone Radio tab content, ready to mount in Hush navigation.
 * Tap a station to play; long press occupied OR empty slot to swap/move.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PolarisRadioGrid(
    layout: PolarisRadioLayout,
    onPlayStation: (String) -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    var editingSlot by remember { mutableIntStateOf(-1) }
    val slots = remember(revision) { layout.slots() }
    Column {
        repeat(4) { rowIndex ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { columnIndex ->
                    val slot = rowIndex * 4 + columnIndex
                    val stationId = slots[slot]
                    val station = stationId?.let(RadioCatalog::find)
                    Text(
                        text = station?.title ?: "＋",
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = { stationId?.let(onPlayStation) },
                                onLongClick = { editingSlot = slot },
                            ),
                    )
                }
            }
        }
    }
    if (editingSlot >= 0) {
        AlertDialog(
            onDismissRequest = { editingSlot = -1 },
            title = { Text("${editingSlot + 1}번 프리셋 변경 (기존 위치와 교환)") },
            text = {
                Column {
                    RadioCatalog.stations.forEach { station ->
                        TextButton(onClick = {
                            layout.swap(editingSlot, station.id)
                            revision++
                            editingSlot = -1
                        }) { Text(station.title) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingSlot = -1 }) { Text("취소") }
            },
        )
    }
}
