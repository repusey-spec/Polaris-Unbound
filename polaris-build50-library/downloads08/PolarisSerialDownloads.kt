package app.hush.music.polaris

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One worker owns address resolution, bytes, verification and the completion commit. */
class PolarisSerialDownloads(
    private val scope: CoroutineScope,
    restored: List<Task> = emptyList(),
    private val persist: (List<Task>) -> Unit,
    private val changed: (Task) -> Unit,
    private val failed: (Task, Exception) -> Unit,
    private val fatal: (Throwable) -> Unit = {},
    private val execute: suspend (Task) -> Unit,
) {
    enum class State { QUEUED, ACTIVE, PAUSED, FAILED, COMPLETE }
    data class Task(val id: String, val title: String, val token: String = UUID.randomUUID().toString(), val state: State = State.QUEUED,
        val attempts: Int = 1, val updatedAt: Long = System.currentTimeMillis(),
        val stage: String = "대기", val error: String = "", val bytes: Long = 0L,
        val expected: Long = -1L, val tempPath: String = "")
    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var wanted = false
    private var active: Pair<Task, Job>? = null
    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending
    private val mutableTasks = MutableStateFlow<List<Task>>(emptyList())
    val entries: StateFlow<List<Task>> = mutableTasks
    private fun publishLocked() { mutableTasks.value = tasks.values.toList() }
    fun progress(id: String, stage: String, bytes: Long = 0L, expected: Long = -1L, tempPath: String = "") = synchronized(lock) {
        val old = tasks[id] ?: return@synchronized
        if (old.state != State.ACTIVE) return@synchronized
        val next = old.copy(stage = stage, bytes = bytes, expected = expected,
            tempPath = tempPath.ifBlank { old.tempPath }, updatedAt = System.currentTimeMillis())
        tasks[id] = next
        if (old.stage != stage || (tempPath.isNotBlank() && tempPath != old.tempPath)) persist(tasks.values.toList())
        publishLocked()
    }

    init {
        restored.filter { it.id.isNotBlank() && it.state != State.COMPLETE }.forEach {
            // A process death does not turn a half-written transfer into a completed file.
            tasks[it.id] = if (it.state == State.ACTIVE) it.copy(state = State.QUEUED) else it
        }
        mutablePending.value = tasks.values.any { it.state == State.QUEUED }; publishLocked()
    }
    fun snapshot(): List<Task> = synchronized(lock) { tasks.values.toList() }
    fun contains(id: String): Boolean = synchronized(lock) { id in tasks }
    fun activeId(): String? = synchronized(lock) { active?.first?.id }
    private fun saveLocked() {
        persist(tasks.values.toList())
        publishLocked()
        mutablePending.value = active != null || tasks.values.any { it.state == State.QUEUED }
    }
    fun enqueue(id: String, title: String) = synchronized(lock) {
        val old = tasks[id]
        if (old?.state == State.QUEUED || old?.state == State.ACTIVE) return@synchronized
        val task = Task(id, title, attempts = (old?.attempts ?: 0) + 1)
        tasks[id] = task
        try { saveLocked() } catch (error: Exception) {
            if (old == null) tasks.remove(id) else tasks[id] = old
            throw error
        }
        changed(task)
        wake.trySend(Unit)
    }
    fun pause(id: String) = synchronized(lock) {
        val old = tasks[id] ?: return@synchronized
        if (old.state != State.QUEUED && old.state != State.ACTIVE) return@synchronized
        val paused = old.copy(state = State.PAUSED)
        tasks[id] = paused
        saveLocked()
        changed(paused)
        active?.takeIf { it.first.id == id }?.second?.cancel(CancellationException("download paused"))
    }
    fun remove(id: String) = synchronized(lock) {
        tasks.remove(id)
        saveLocked()
        active?.takeIf { it.first.id == id }?.second?.cancel(CancellationException("download removed"))
    }
    fun retryFailed(id: String, cleanup: (Task) -> Unit): Boolean = synchronized(lock) {
        val old = tasks[id] ?: return@synchronized false
        if (old.state != State.FAILED) return@synchronized false
        cleanup(old)
        enqueue(id, old.title)
        true
    }
    fun discardFailed(id: String, cleanup: (Task) -> Unit): Boolean = synchronized(lock) {
        val old = tasks[id] ?: return@synchronized false
        if (old.state != State.FAILED) return@synchronized false
        cleanup(old)
        tasks.remove(id)
        try { saveLocked() } catch (error: Exception) { tasks[id] = old; publishLocked(); throw error }
        true
    }
    fun pauseAll() { snapshot().forEach { pause(it.id) } }
    fun resumeAll() { snapshot().filter { it.state == State.PAUSED }.forEach { enqueue(it.id, it.title) } }
    fun clear() { snapshot().forEach { remove(it.id) } }

    /** Called by the foreground service, never by merely opening the library. */
    fun start(): Unit = synchronized(lock) {
        wanted = true
        if (worker?.isCompleted == false) { wake.trySend(Unit); return@synchronized }
        worker = scope.launch(CoroutineExceptionHandler { _, error ->
            synchronized(lock) { active = null; mutablePending.value = false }
            fatal(error)
        }) {
            try {
            for (signal in wake) {
                while (currentCoroutineContext().isActive) {
                    val next = synchronized(lock) { tasks.values.firstOrNull { it.state == State.QUEUED } } ?: break
                    supervisorScope {
                        val task = next.copy(state = State.ACTIVE, stage = "주소 조회", updatedAt = System.currentTimeMillis())
                        val child = async(start = CoroutineStart.LAZY) { execute(task) }
                        val claimed = synchronized(lock) {
                            if (tasks[task.id]?.token != task.token || tasks[task.id]?.state != State.QUEUED) false
                            else {
                                tasks[task.id] = task
                                active = task to child
                                saveLocked()
                                changed(task)
                                true
                            }
                        }
                        if (!claimed) { child.cancel(); return@supervisorScope }
                        try {
                            child.start()
                            child.await()
                            synchronized(lock) {
                                if (tasks[task.id]?.token == task.token && tasks[task.id]?.state == State.ACTIVE) {
                                    tasks.remove(task.id)
                                    changed(task.copy(state = State.COMPLETE))
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            // User cancellation stays a pause/removal; it never kills the worker.
                        } catch (error: Exception) {
                            synchronized(lock) {
                                if (tasks[task.id]?.token == task.token && tasks[task.id]?.state == State.ACTIVE) {
                                    val failure = tasks.getValue(task.id).copy(state = State.FAILED,
                                        error = PolarisDownloadPolicy.reason(error), updatedAt = System.currentTimeMillis(),
                                        tempPath = if (error is PolarisVerificationTimeout) tasks.getValue(task.id).tempPath else "")
                                    tasks[task.id] = failure
                                    changed(failure)
                                    failed(failure, error)
                                }
                            }
                        } finally {
                            // await() waits for blocking reads and cleanup to really finish. Only
                            // after that may this single worker claim the next file, even on resume.
                            withContext(NonCancellable) { child.cancelAndJoin() }
                            synchronized(lock) {
                                active = null
                                val current = tasks[task.id]
                                if (current?.token == task.token && current.state == State.ACTIVE) {
                                    tasks[task.id] = current.copy(state = State.QUEUED)
                                }
                                saveLocked()
                                tasks[task.id]?.let(changed)
                            }
                        }
                    }
                }
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                synchronized(lock) {
                    active = null
                    tasks.replaceAll { _, task -> if (task.state == State.ACTIVE) task.copy(state = State.QUEUED) else task }
                    mutablePending.value = false
                }
                fatal(error)
            }
        }
        val current = worker!!
        current.invokeOnCompletion { error ->
            synchronized(lock) {
                if (worker === current) worker = null
                // Lifecycle restart waits until the previous worker's cancellation cleanup.
                if (wanted && scope.isActive && error is CancellationException) start()
            }
        }
        wake.trySend(Unit)
    }
    fun stop() = synchronized(lock) { wanted = false; worker?.cancel() }
}

