package org.openup.ui.folders

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openup.container
import org.openup.data.model.FolderBackup
import org.openup.data.special.FolderEngine
import org.openup.jobs.JobKind
import org.openup.jobs.JobRequest
import org.openup.jobs.JobTask
import org.openup.shizuku.ShizukuStatus
import java.io.File

class FoldersViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val settings = c.settings.state
    val job = c.jobs.state
    val shizuku = c.shizuku.status

    private val _backups = MutableStateFlow<List<FolderBackup>>(emptyList())
    val backups = _backups.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        refresh()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            _backups.value = withContext(Dispatchers.IO) {
                if (c.backupsReadable()) c.storage.folderBackups() else emptyList()
            }
        }
    }

    /** Adds [raw] to the folder list. Returns an error message or null on success. */
    fun add(raw: String): String? {
        val path = FolderEngine.normalize(raw)
        c.folders.validate(path)?.let { return it }
        if (path in c.settings.current.folders) return "This folder is already in the list"
        c.settings.update { it.copy(folders = it.folders + path) }
        return null
    }

    fun remove(path: String) {
        c.settings.update { it.copy(folders = it.folders - path) }
    }

    fun backup(paths: List<String>): Boolean {
        if (paths.isEmpty()) return false
        val title = if (paths.size == 1) "Backup of ${File(paths[0]).name}" else "Backup of ${paths.size} folders"
        return c.jobs.start(getApplication(), JobRequest(title, JobKind.BACKUP, paths.map { JobTask.BackupFolder(it) }))
    }

    fun restore(b: FolderBackup): Boolean {
        val path = b.path ?: return false
        return c.jobs.start(getApplication(), JobRequest("Restore of ${File(path).name}", JobKind.RESTORE, listOf(JobTask.RestoreFolder(path, b.dir.id))))
    }

    fun delete(b: FolderBackup) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { c.storage.delete(b.dir) }
            _message.value = if (ok) "Backup deleted" else "Could not delete the backup completely"
            refresh()
        }
    }

    /** Sub-folders of [path] for the folder picker, using Shizuku when available (Android/data). */
    suspend fun listDirectories(path: String): List<String>? = withContext(Dispatchers.IO) {
        if (c.shizuku.status.value is ShizukuStatus.Ready) {
            runCatching { c.shell.listDirectories(path) }.getOrNull()?.let { return@withContext it }
        }
        File(path).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
