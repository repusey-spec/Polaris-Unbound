package app.hush.music.polaris

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Process-death recovery; no stream URLs or credentials are stored in this queue. */
class PolarisDownloadStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "polaris_download_queue_07.json"))
    @Synchronized fun read(): List<PolarisSerialDownloads.Task> = try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList()
        else {
            val rows = JSONArray(String(file.readFully(), Charsets.UTF_8))
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                PolarisSerialDownloads.Task(row.getString("id"), row.optString("title"), row.getString("token"),
                    PolarisSerialDownloads.State.valueOf(row.getString("state")),
                    attempts = row.optInt("attempts", 1), updatedAt = row.optLong("updatedAt", 0),
                    stage = row.optString("stage", "대기"), error = row.optString("error", "이전 실패 · 오류 리포트 확인"),
                    bytes = row.optLong("bytes", 0), expected = row.optLong("expected", -1), tempPath = row.optString("tempPath"))
            }
        }
    } catch (error: Exception) {
        PolarisErrorReport.failure("download-queue-restore", error)
        emptyList()
    }
    @Synchronized fun write(tasks: List<PolarisSerialDownloads.Task>) {
        val rows = JSONArray()
        tasks.forEach { task -> rows.put(JSONObject().put("id", task.id).put("title", task.title)
            .put("token", task.token).put("state", task.state.name)
            .put("attempts", task.attempts).put("updatedAt", task.updatedAt).put("stage", task.stage)
            .put("error", task.error).put("bytes", task.bytes).put("expected", task.expected).put("tempPath", task.tempPath)) }
        val stream = file.startWrite()
        try {
            val bytes = rows.toString().toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            file.finishWrite(stream)
            if (!file.readFully().contentEquals(bytes)) throw java.io.IOException("download queue commit not durable")
        } catch (error: Exception) {
            file.failWrite(stream)
            PolarisErrorReport.failure("download-queue-save", error)
            throw error
        }
    }
}

