package app.hush.music.polaris

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import app.hush.music.db.MusicDatabase
import app.hush.music.playback.DownloadUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class PolarisDownloadsActivity : ComponentActivity() {
    @Inject lateinit var downloadUtil: DownloadUtil
    @Inject lateinit var database: MusicDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFF3A7C9))) {
                val tasks by downloadUtil.serialDownloadTasks.collectAsStateWithLifecycle()
                val downloads by downloadUtil.downloads.collectAsStateWithLifecycle()
                val songs by database.allSongs().collectAsStateWithLifecycle(initialValue = emptyList())
                val byId = remember(songs) { songs.associateBy { it.id } }
                var filter by remember { mutableStateOf("전체") }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                val failed = tasks.filter { it.state == PolarisSerialDownloads.State.FAILED }
                val active = tasks.firstOrNull { it.state == PolarisSerialDownloads.State.ACTIVE }
                val visible = tasks.filter { when (filter) {
                    "실패" -> it.state == PolarisSerialDownloads.State.FAILED
                    "진행 중" -> it.state == PolarisSerialDownloads.State.ACTIVE || it.state == PolarisSerialDownloads.State.QUEUED
                    else -> true
                } }
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Column {
                                TextButton(onClick = { finish() }) { Text("뒤로") }
                                Text("다운로드 관리", style = MaterialTheme.typography.headlineSmall)
                                Text("정상 저장 ${downloads.values.count { it.state == Download.STATE_COMPLETED }} · 실패 ${failed.size}")
                                Text("대기 ${tasks.count { it.state == PolarisSerialDownloads.State.QUEUED }} · 일시정지 ${tasks.count { it.state == PolarisSerialDownloads.State.PAUSED }}")
                                Text(active?.let { "${it.title.ifBlank { it.id }} · ${it.stage} · ${((now-it.updatedAt).coerceAtLeast(0)/1000)}초" }
                                    ?: if (failed.isNotEmpty()) "진행 중인 작업 없음 · 실패 항목을 확인해 주세요" else "진행 중인 작업 없음")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("전체", "진행 중", "실패").forEach { label ->
                                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                                    }
                                }
                                Row {
                                    TextButton(enabled = failed.isNotEmpty(), onClick = { downloadUtil.retryAllFailedDownloads() }) { Text("실패 전체 재시도") }
                                    TextButton(enabled = failed.isNotEmpty(), onClick = { failed.forEach { downloadUtil.discardFailedDownload(it.id) } }) { Text("실패 전체 정리") }
                                }
                                TextButton(onClick = { PolarisErrorReport.open(this@PolarisDownloadsActivity) }) { Text("오류 리포트 보기·공유") }
                                Text("정리는 실패 기록과 미완료 파일을 삭제합니다. 좋아요·보관함·정상 저장곡은 유지됩니다.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(visible, key = { it.id }) { task ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    val song = byId[task.id]
                                    Text(song?.song?.title ?: task.title.ifBlank { task.id }, style = MaterialTheme.typography.titleMedium)
                                    Text(song?.artists?.joinToString(", ") { it.name }.orEmpty())
                                    Text(when (task.state) {
                                        PolarisSerialDownloads.State.FAILED -> "실패 · ${task.error.ifBlank { "이전 실패 · 오류 리포트 확인" }}"
                                        PolarisSerialDownloads.State.PAUSED -> "일시정지"
                                        PolarisSerialDownloads.State.QUEUED -> "대기"
                                        else -> task.stage
                                    })
                                    if (task.expected > 0) Text("${task.bytes} / ${task.expected} 바이트")
                                    val time = if (task.updatedAt > 0) SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(task.updatedAt)) else "이전 버전 기록"
                                    Text("$time · 시도 ${task.attempts}회", style = MaterialTheme.typography.bodySmall)
                                    if (task.state == PolarisSerialDownloads.State.FAILED) {
                                        Text(if (task.tempPath.isBlank()) "보존된 미완료 파일 없음" else "미완료 파일 보존됨", style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            TextButton(onClick = { downloadUtil.retryFailedDownload(task.id) }) { Text("다시 다운로드") }
                                            TextButton(onClick = { downloadUtil.discardFailedDownload(task.id) }) { Text("실패 항목 삭제") }
                                        }
                                    }
                                }
                            }
                        }
                        if (visible.isEmpty()) item { Text("해당 다운로드 작업이 없습니다.") }
                        if (filter == "전체") items(downloads.values.filter { it.state == Download.STATE_COMPLETED }.toList(), key = { "done:${it.request.id}" }) { row ->
                            val song = byId[row.request.id]
                            Text("✓ ${song?.song?.title ?: String(row.request.data, Charsets.UTF_8).ifBlank { row.request.id }} · 저장 완료")
                        }
                    }
                }
            }
        }
    }
    companion object { fun open(context: Context) { context.startActivity(Intent(context, PolarisDownloadsActivity::class.java)) } }
}
