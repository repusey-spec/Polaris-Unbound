package app.hush.music.polaris

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import app.hush.music.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Own-process diagnostics + public device pressure signals; never claims kernel/system-wide access. */
object PolarisResourceDiagnostics {
    private val installed = AtomicBoolean()
    private val engines = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private val heartbeatPending = AtomicBoolean()
    @Volatile private var heartbeatAt = 0L
    @Volatile private var lastStackAt = -60_000L
    @Volatile private var aa = "unknown"
    @Volatile private var playback = "service=absent"
    @Volatile private var metrics = "not sampled"
    @Volatile private var lastSample = -60_000L
    private val monitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PolarisWatchdog").apply { isDaemon = true } }
    private val sampler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PolarisResourceSampler").apply { isDaemon = true } }
    private val sampling = AtomicBoolean()
    private var directory: File? = null
    fun event(value: String) = PolarisDiagnosticJournal.record("resource", value)
    fun engine(delta: Int) { event("botguard engines=${engines.addAndGet(delta)} change=$delta") }
    fun projection(state: Int) { aa = state.toString(); event("AA state=$state") }
    fun player(value: String) { playback = value.take(1_000); event("player $playback") }
    fun trim(level: Int) { event("memory-trim level=$level"); requestSample() }
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != context.packageName) return
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        PolarisDiagnosticJournal.install(app)
        directory = File(app.filesDir, "polaris-diagnostics11")
        sampler.execute {
            runCatching {
                directory?.mkdirs()
                val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
                val marker = File(directory, "launch.txt")
                if (marker.exists()) event("previous-launch ${marker.readText().take(256)}; unclean/forced death cannot be inferred from this marker")
                marker.writeText("wall=${System.currentTimeMillis()} boot=$boot pid=${Process.myPid()} build=${BuildConfig.VERSION_NAME}")
                event("process-start boot=$boot pid=${Process.myPid()} build=${BuildConfig.VERSION_NAME}")
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                if (Build.VERSION.SDK_INT >= 30) {
                    val trace = StringBuilder()
                    for (exit in am.getHistoricalProcessExitReasons(app.packageName, 0, 8)) {
                        event("previous-exit wall=${exit.timestamp} process=${exit.processName} reason=${exit.reason} status=${exit.status} importance=${exit.importance} reasonName=${exitReason(exit.reason)} pssKb=${exit.pss} rssKb=${exit.rss}")
                        if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR && trace.length < 48_000) {
                            runCatching {
                                exit.traceInputStream?.bufferedReader()?.use { reader ->
                                    val chars = CharArray((48_000 - trace.length).coerceAtLeast(1))
                                    val count = reader.read(chars)
                                    if (count > 0) trace.append("exit=${exit.timestamp}\n").append(chars, 0, count)
                                }
                            }
                        }
                    }
                    if (trace.isNotEmpty()) File(directory, "previous-anr.txt").writeText(PolarisSourcePolicy.redact(trace.toString()).take(48_000))
                }
            }.onFailure { event("exit-history unavailable=${it.javaClass.simpleName}") }
        }
        monitor.scheduleWithFixedDelay({ runCatching {
            val now = SystemClock.elapsedRealtime()
            if (heartbeatPending.get()) {
                val lag = SystemClock.uptimeMillis() - heartbeatAt
                if (lag >= 3_000 && now - lastStackAt >= 30_000) {
                    lastStackAt = now
                    event("main-stall delayMs=$lag AA=$aa $playback $metrics")
                    event("main-stack ${Looper.getMainLooper().thread.stackTrace.take(20).joinToString(" <- ")}")
                    runCatching {
                        Thread.getAllStackTraces().entries.asSequence()
                            .filter { (t, _) -> listOf("ExoPlayer", "Audio", "DefaultDispatcher", "OkHttp", "PolarisJournal").any { t.name.contains(it) } }
                            .take(8).forEach { (thread, stack) -> event("worker-stack name=${thread.name.take(80)} state=${thread.state} ${stack.take(12).joinToString(" <- ")}") }
                    }
                }
            } else if (heartbeatPending.compareAndSet(false, true)) {
                heartbeatAt = SystemClock.uptimeMillis()
                main.post {
                    val lag = SystemClock.uptimeMillis() - heartbeatAt
                    if (lag >= 1_000) event("main-resumed delayMs=$lag")
                    heartbeatPending.set(false)
                }
            }
            if (now - lastSample >= 30_000 && sampling.compareAndSet(false, true)) {
                lastSample = now
                sampler.execute { try { sample(app) } finally { sampling.set(false) } }
            }
        } }, 1, 2, TimeUnit.SECONDS)
    }
    fun fatal(thread: Thread, error: Throwable) {
            runCatching {
                val text = buildString {
                    appendLine("uncaught wall=${System.currentTimeMillis()} build=${BuildConfig.VERSION_NAME} thread=${thread.name} type=${error.javaClass.name}")
                    appendLine(metrics)
                    error.stackTrace.take(28).forEach { appendLine(it.toString()) }
                }
                directory?.mkdirs()
                File(directory, "uncaught.txt").writeText(PolarisSourcePolicy.redact(text).take(12_000))
            }
    }
    private fun requestSample() { lastSample = -60_000L }
    private fun sample(app: Context) {
        runCatching {
            val runtime = Runtime.getRuntime()
            val memory = ActivityManager.MemoryInfo()
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(memory)
            val own = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val fdCount = File("/proc/self/fd").list()?.size ?: -1
            val threads = File("/proc/self/task").list()?.size ?: -1
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
            val battery = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val cpuMs = Process.getElapsedCpuTime()
            metrics = "heap=${runtime.totalMemory() - runtime.freeMemory()}/${runtime.maxMemory()} native=${Debug.getNativeHeapAllocatedSize()} pssKb=${own.totalPss} fd=$fdCount threads=$threads cpuMs=$cpuMs sysAvail=${memory.availMem} sysLow=${memory.lowMemory} sysThreshold=${memory.threshold} thermal=$thermal batteryTempTenthC=${battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)} storageAvail=${StatFs(app.filesDir.path).availableBytes} engines=${engines.get()}"
            event("sample AA=$aa $playback $metrics")
            if (Build.VERSION.SDK_INT >= 30) am.setProcessStateSummary("AA=$aa $metrics".take(120).toByteArray(Charsets.US_ASCII))
        }.onFailure { event("sample unavailable=${it.javaClass.simpleName}") }
    }
    private fun exitReason(reason: Int): String = when(reason) {
        1 -> "exit-self"; 2 -> "signal"; 3 -> "low-memory"; 4 -> "java-crash"; 5 -> "native-crash"
        6 -> "ANR"; 7 -> "initialization"; 8 -> "permission-change"; 9 -> "excessive-resources"
        10 -> "user-request"; 11 -> "user-stop"; 12 -> "dependency-death"; 13 -> "other"; else -> "platform-$reason"
    }
    fun dump(): String = buildString {
        appendLine("Coverage: own app/process, public device memory/thermal/storage pressure, AA/audio callbacks, available own-process exit/ANR history.")
        appendLine("Unavailable without privileged capture: kernel/watchdog, full system logcat, other apps' heaps, AudioFlinger internals. No automatic bugreport/ADB capture. A missing ANR record does not exclude a phone-wide freeze/reboot.")
        appendLine("sampling=30s; Main heartbeat=2s (uptime, excluding sleep); wall=epoch ms; mono=elapsedRealtime ms")
        appendLine(PolarisDiagnosticJournal.dump("resource"))
        for (name in listOf("uncaught.txt", "previous-anr.txt")) runCatching {
            val file = File(directory, name)
            if (file.exists()) { appendLine("=== $name ==="); appendLine(file.readText().take(48_000)) }
        }
    }
}
