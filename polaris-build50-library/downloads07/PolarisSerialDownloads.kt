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
    data class Task(val id: String, val title: String, val token: String = UUID.randomUUID().toString(), val state: State = State.QUEUED)
    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var wanted = false
    private var active: Pair<Task, Job>? = null
    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending

    init {
        restored.filter { it.id.isNotBlank() && it.state != State.COMPLETE }.forEach {
            // A process death does not turn a half-written transfer into a completed file.
            tasks[it.id] = if (it.state == State.ACTIVE) it.copy(state = State.QUEUED) else it
        }
        mutablePending.value = tasks.values.any { it.state == State.QUEUED }
    }
    fun snapshot(): List<Task> = synchronized(lock) { tasks.values.toList() }
    fun contains(id: String): Boolean = synchronized(lock) { id in tasks }
    fun activeId(): String? = synchronized(lock) { active?.first?.id }
    private fun saveLocked() {
        persist(tasks.values.toList())
        mutablePending.value = active != null || tasks.values.any { it.state == State.QUEUED }
    }
    fun enqueue(id: String, title: String) = synchronized(lock) {
        val old = tasks[id]
        if (old?.state == State.QUEUED || old?.state == State.ACTIVE) return@synchronized
        val task = Task(id, title)
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
    fun pauseAll() { snapshot().forEach { pause(it.id) } }
    fun resumeAll() { snapshot().filter { it.state == State.PAUSED }.forEach { enqueue(it.id, it.title) } }
    fun clear() { snapshot().forEach { remove(it.id) } }

    /** Called by the foreground service, never by merely opening the library. */
    fun start() = synchronized(lock) {
        wanted = true
        if (worker?.isCompleted == false) { wake.trySend(Unit); return@synchronized }
        worker = scope.launch(CoroutineExceptionHandler { _, error ->
            synchronized(lock) { active = null; mutablePending.value = false }
            fatal(error)
        }) {
            for (signal in wake) {
                while (currentCoroutineContext().isActive) {
                    val next = synchronized(lock) { tasks.values.firstOrNull { it.state == State.QUEUED } } ?: break
                    supervisorScope {
                        val task = next.copy(state = State.ACTIVE)
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
                                    val failure = task.copy(state = State.FAILED)
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
                synchronized(lock) { saveLocked() }
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
