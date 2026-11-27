package org.openup.jobs

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openup.AppContainer
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.TaskOutcome
import org.openup.data.model.SpecialType
import org.openup.shizuku.ShizukuStatus
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Runs backup/restore jobs one at a time and publishes their progress. */
class JobManager(private val c: AppContainer) {
    private val _state = MutableStateFlow<JobState?>(null)
    val state: StateFlow<JobState?> = _state.asStateFlow()

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: JobRequest? = null
    private var nextId = 1L

    /**
     * True from the moment a job is accepted until it has completely finished. Only one job can
     * hold the reservation, which makes starting jobs race free.
     */
    private var reserved = false

    @Volatile
    private var cancelRequested = false

    val isBusy: Boolean get() = synchronized(lock) { reserved }

    /** Reserves the job runner for the caller. Must be followed by [release] once the job ended. */
    fun tryReserve(): Boolean = synchronized(lock) {
        if (reserved) return false
        reserved = true
        cancelRequested = false
        true
    }

    fun release() {
        synchronized(lock) { reserved = false }
    }

    /** Starts [request] in the foreground service. Returns false if a job is already running. */
    fun start(context: Context, request: JobRequest): Boolean {
        if (request.tasks.isEmpty()) return false
        if (!tryReserve()) return false
        synchronized(lock) { pending = request }
        val previous = _state.value
        _state.value = JobState(0, request.title, request.kind, request.tasks.map { TaskState(it) }, running = true)
        return try {
            ContextCompat.startForegroundService(context, Intent(context, BackupJobService::class.java))
            true
        } catch (e: Exception) {
            synchronized(lock) { pending = null }
            _state.value = previous
            release()
            false
        }
    }

    fun takePending(): JobRequest? = synchronized(lock) { pending.also { pending = null } }

    fun cancel() {
        cancelRequested = true
        _state.update { it?.copy(cancelRequested = true) }
    }

    /** Clears the result of a finished job (e.g. after the user dismissed it). */
    fun clearFinished() {
        _state.update { if (it?.running == true) it else null }
    }

    private fun updateTask(index: Int, transform: (TaskState) -> TaskState) {
        _state.update { s ->
            s?.copy(tasks = s.tasks.mapIndexed { i, t -> if (i == index) transform(t) else t })
        }
    }

    /**
     * Runs [request] to completion on the calling coroutine and returns the final state.
     * The caller must hold the reservation (see [tryReserve] / [start]).
     */
    suspend fun run(request: JobRequest): JobState = mutex.withLock {
        val id = nextId++
        _state.value = JobState(id, request.title, request.kind, request.tasks.map { TaskState(it) }, running = true)
        val crypto = c.passwords.session()
        try {
            val storageProblem = ensureStorage()
            for ((index, task) in request.tasks.withIndex()) {
                if (cancelRequested || !currentCoroutineContext().isActive) {
                    markRemainingCancelled()
                    break
                }
                if (storageProblem != null) {
                    updateTask(index) { it.copy(status = TaskStatus.FAILED, message = storageProblem) }
                    continue
                }
                updateTask(index) { it.copy(status = TaskStatus.RUNNING, step = "Starting", progress = -1f) }
                try {
                    val sink = Sink(index, currentCoroutineContext())
                    // Engines do blocking file and Binder I/O.
                    val outcome = withContext(Dispatchers.IO) { runTask(task, crypto, sink) }
                    updateTask(index) {
                        it.copy(
                            status = if (outcome.warnings.isEmpty()) TaskStatus.DONE else TaskStatus.WARNING,
                            message = outcome.summary,
                            warnings = outcome.warnings,
                            progress = 1f,
                            step = "",
                        )
                    }
                } catch (e: CancellationException) {
                    updateTask(index) { it.copy(status = TaskStatus.CANCELLED, message = "Cancelled", step = "") }
                    markRemainingCancelled()
                    if (!currentCoroutineContext().isActive) throw e
                    break
                } catch (t: Throwable) {
                    updateTask(index) { it.copy(status = TaskStatus.FAILED, message = describe(t), step = "") }
                }
            }
        } finally {
            crypto?.key?.wipe()
            _state.update { it?.copy(running = false, finishedAt = System.currentTimeMillis()) }
        }
        _state.value!!
    }

    private fun markRemainingCancelled() {
        _state.update { s ->
            s?.copy(tasks = s.tasks.map {
                if (it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING) {
                    it.copy(status = TaskStatus.CANCELLED, message = "Cancelled", step = "")
                } else {
                    it
                }
            })
        }
    }

    private suspend fun ensureStorage(): String? {
        if (c.privileges.hasStorageAccess()) return null
        if (c.shizuku.status.value is ShizukuStatus.Ready) {
            runCatching { c.privileges.grantStorageAccess() }
            if (c.privileges.hasStorageAccess()) return null
        }
        return "OpenUp has no access to the backup folder. Open OpenUp and finish the setup."
    }

    private suspend fun runTask(task: JobTask, crypto: CryptoSession?, sink: ProgressSink): TaskOutcome {
        if (task.needsShizuku) c.shizuku.requireReady()
        return when (task) {
            is JobTask.BackupApp -> c.appBackup.backup(task.packageName, task.parts, crypto, sink)
            is JobTask.RestoreApp -> {
                val backup = task.backupDir?.let { c.storage.readAppBackup(File(it)) }
                    ?: c.storage.appBackups(task.packageName).firstOrNull()
                    ?: throw IOException("No backup found")
                c.appRestore.restore(backup, task.parts, crypto, sink)
            }
            is JobTask.BackupSpecial -> {
                ensureSpecialPermissions(task.type, restore = false)
                withContext(Dispatchers.IO) {
                    when (task.type) {
                        SpecialType.MESSAGES -> c.messages.backup(crypto, sink)
                        SpecialType.CALL_LOGS -> c.callLogs.backup(crypto, sink)
                        SpecialType.WALLPAPERS -> c.wallpapers.backup(crypto, sink)
                        SpecialType.WIFI -> c.wifi.backup(crypto, sink)
                    }
                }
            }
            is JobTask.RestoreSpecial -> {
                ensureSpecialPermissions(task.type, restore = true)
                val backup = c.storage.readSpecialBackup(task.type, File(task.backupDir))
                    ?: throw IOException("Backup not found or unreadable")
                withContext(Dispatchers.IO) {
                    when (task.type) {
                        SpecialType.MESSAGES -> c.messages.restore(backup, crypto, sink)
                        SpecialType.CALL_LOGS -> c.callLogs.restore(backup, crypto, sink)
                        SpecialType.WALLPAPERS -> c.wallpapers.restore(backup, crypto, sink)
                        SpecialType.WIFI -> c.wifi.restore(backup, crypto, sink)
                    }
                }
            }
        }
    }

    private suspend fun ensureSpecialPermissions(type: SpecialType, restore: Boolean) {
        val p = c.privileges
        val shizukuReady = c.shizuku.status.value is ShizukuStatus.Ready
        when (type) {
            SpecialType.MESSAGES -> {
                if (!p.has(android.Manifest.permission.READ_SMS) && shizukuReady) p.grantSmsRead()
                if (!p.has(android.Manifest.permission.READ_SMS)) {
                    throw IOException("OpenUp needs permission to read SMS. Grant it on the Messages screen.")
                }
                if (restore) {
                    if (!p.canWriteSms() && shizukuReady) p.grantSmsWrite()
                    if (!p.canWriteSms()) throw IOException("OpenUp is not allowed to write SMS. Start Shizuku and try again.")
                }
            }
            SpecialType.CALL_LOGS -> {
                if (!p.hasCallLog() && shizukuReady) p.grantCallLog()
                if (!p.hasCallLog()) throw IOException("OpenUp needs call log permissions. Grant them on the Call logs screen.")
            }
            SpecialType.WALLPAPERS, SpecialType.WIFI -> Unit
        }
    }

    private inner class Sink(private val index: Int, private val ctx: CoroutineContext) : ProgressSink {
        private var total = -1L
        private var done = 0L
        private var lastEmit = 0L
        private var label = ""

        private fun checkCancel() {
            if (cancelRequested || !ctx.isActive) throw CancellationException("Cancelled")
        }

        override fun step(label: String, totalBytes: Long) {
            checkCancel()
            this.label = label
            total = totalBytes
            done = 0
            emit(force = true)
        }

        override fun bytes(count: Long) {
            checkCancel()
            done += count
            emit(force = false)
        }

        private fun emit(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < 250) return
            lastEmit = now
            val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
            val l = label
            updateTask(index) { it.copy(step = l, progress = p) }
        }
    }

    companion object {
        fun describe(t: Throwable): String {
            val msg = t.message?.takeIf { it.isNotBlank() }
            return when (t) {
                is OutOfMemoryError -> "Out of memory"
                is SecurityException -> "Permission denied: ${msg ?: "unknown"}"
                else -> msg ?: t.javaClass.simpleName
            }
        }
    }
}
