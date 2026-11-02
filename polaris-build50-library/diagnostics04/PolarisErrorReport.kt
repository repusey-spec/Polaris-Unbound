package app.hush.music.polaris

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import app.hush.music.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded local report. Never records search queries, cookies or complete URLs. */
object PolarisErrorReport {
    @Volatile private var app: Context? = null
    @Volatile private var aaState = "unknown"
    private const val PREFS = "polaris_error_report_04"
    private const val LIMIT = 96_000

    fun install(context: Context) {
        app = context.applicationContext
        record("service-start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
    }

    @Synchronized fun record(event: String) {
        val context = app ?: return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$clock ${PolarisSourcePolicy.redact(event).take(8_000)}\n"
            prefs.edit().putString("events", (prefs.getString("events", "").orEmpty() + line).takeLast(LIMIT)).apply()
        }
    }

    fun connection(state: Int) { aaState = state.toString(); record("AA projection-state=$state") }

    fun item(item: MediaItem?): String = if (item == null) "item=none" else {
        val config = item.localConfiguration
        "id=${PolarisSourcePolicy.redact(item.mediaId)} source=${if (PolarisSourcePolicy.isRadio(item.mediaId)) "radio" else "streaming"}" +
            " uri=${config?.uri?.scheme ?: "id-or-missing"} key=${config?.customCacheKey != null} mime=${config?.mimeType ?: "auto"}"
    }

    fun failure(stage: String, error: Throwable, item: MediaItem? = null) {
        runCatching {
            val detail = buildString {
                appendLine("ERROR stage=$stage ${PolarisErrorReport.item(item)}")
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                var cause: Throwable? = error
                var level = 0
                while (cause != null && level < 12 && seen.add(cause)) {
                    val current = cause
                    appendLine("cause[$level] ${current.javaClass.name}: ${current.message}")
                    if (current is androidx.media3.common.PlaybackException) appendLine("player-code=${current.errorCode} name=${current.errorCodeName}")
                    if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) appendLine("HTTP status=${current.responseCode}")
                    current.stackTrace.take(10).forEach { appendLine("  at $it") }
                    cause = current.cause
                    level++
                }
            }
            val safe = PolarisSourcePolicy.redact(detail).take(20_000)
            record(safe)
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("last-error", safe)?.apply()
        }
    }

    fun dump(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return buildString {
            appendLine("Polaris ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
            appendLine("Device=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT}")
            appendLine("Generated=${Date()} AA-state=$aaState (unknown after process restart)")
            appendLine("=== LAST ERROR ===")
            appendLine(prefs.getString("last-error", "아직 기록된 오류가 없습니다."))
            appendLine("=== RECENT BROWSE / PLAYBACK EVENTS ===")
            appendLine(prefs.getString("events", "아직 기록된 이벤트가 없습니다."))
            appendLine("=== RADIO / PROJECTION EVENTS ===")
            appendLine(PolarisSourcePolicy.redact(PolarisRadioDiagnostics.dump(context)))
        }
    }

    fun open(context: Context) {
        context.startActivity(Intent(context, PolarisReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Phone-only report screen, reachable from Radio diagnostics and Streaming settings. */
class PolarisReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = PolarisErrorReport.dump(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 20) }
        column.addView(TextView(this).apply { text = "Polaris 오류 리포트 · ${BuildConfig.VERSION_NAME}"; textSize = 20f })
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "기록 복사"
            setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Polaris error report", report)) }
        })
        actions.addView(Button(this).apply {
            text = "TXT 공유"
            setOnClickListener {
                runCatching {
                    val directory = File(cacheDir, "polaris-reports").apply { mkdirs() }
                    val file = File(directory, "Polaris_ErrorReport_${BuildConfig.VERSION_NAME}.txt").apply { writeText(report) }
                    val uri = FileProvider.getUriForFile(this@PolarisReportActivity, "$packageName.FileProvider", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Polaris error report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "오류 리포트 공유"))
                }.onFailure { AlertDialog.Builder(this@PolarisReportActivity).setMessage("공유 실패: ${it.javaClass.simpleName}").setPositiveButton("확인", null).show() }
            }
        })
        actions.addView(Button(this).apply { text = "닫기"; setOnClickListener { finish() } })
        column.addView(actions)
        column.addView(ScrollView(this).apply {
            addView(TextView(this@PolarisReportActivity).apply { text = report; textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
    }
}
