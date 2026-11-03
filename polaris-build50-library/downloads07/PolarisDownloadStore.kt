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
                    PolarisSerialDownloads.State.valueOf(row.getString("state")))
            }
        }
    } catch (error: Exception) {
        PolarisErrorReport.failure("download-queue-restore", error)
        emptyList()
    }
    @Synchronized fun write(tasks: List<PolarisSerialDownloads.Task>) {
        val rows = JSONArray()
        tasks.forEach { task -> rows.put(JSONObject().put("id", task.id).put("title", task.title)
            .put("token", task.token).put("state", task.state.name)) }
        val stream = file.startWrite()
        try {
            stream.write(rows.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            PolarisErrorReport.failure("download-queue-save", error)
            throw error
        }
    }
}
