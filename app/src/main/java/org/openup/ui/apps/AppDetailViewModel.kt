package org.openup.ui.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openup.container
import org.openup.data.AppParts
import org.openup.data.SettingsRepository
import org.openup.data.apps.AppPaths
import org.openup.data.apps.InstalledApp
import org.openup.data.model.AppBackup
import org.openup.jobs.JobKind
import org.openup.jobs.JobRequest
import org.openup.jobs.JobTask
import org.openup.shizuku.ShizukuStatus
import org.openup.ui.components.IconCache

data class DataSizes(val extData: Long = -1, val obb: Long = -1, val media: Long = -1)

class AppDetailViewModel(app: Application, val packageName: String) : AndroidViewModel(app) {
    private val c = app.container
    val job = c.jobs.state
    val shizuku = c.shizuku.status
    val defaultParts get() = c.settings.current.parts

    private val _installed = MutableStateFlow<InstalledApp?>(null)
    val installed = _installed.asStateFlow()
    private val _backups = MutableStateFlow<List<AppBackup>>(emptyList())
    val backups = _backups.asStateFlow()
    private val _sizes = MutableStateFlow<DataSizes?>(null)
    val sizes = _sizes.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        refresh()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            val (i, b) = withContext(Dispatchers.IO) {
                c.installedApps.get(packageName) to
                    (if (c.privileges.hasStorageAccess()) c.storage.appBackups(packageName) else emptyList())
            }
            _installed.value = i
            _backups.value = b
            _loaded.value = true
            if (i != null && c.shizuku.status.value is ShizukuStatus.Ready) {
                _sizes.value = withContext(Dispatchers.IO) {
                    runCatching {
                        DataSizes(
                            c.shell.sizeOf(AppPaths.extData(packageName)),
                            c.shell.sizeOf(AppPaths.obb(packageName)),
                            c.shell.sizeOf(AppPaths.media(packageName)),
                        )
                    }.getOrNull()
                }
            } else {
                _sizes.value = null
            }
        }
    }

    fun backup(parts: AppParts): Boolean {
        val i = _installed.value ?: return false
        return c.jobs.start(getApplication(), JobRequest("Backup of ${i.label}", JobKind.BACKUP, listOf(JobTask.BackupApp(packageName, i.label, parts))))
    }

    fun restore(backup: AppBackup, parts: AppParts): Boolean {
        val label = backup.meta.label
        return c.jobs.start(
            getApplication(),
            JobRequest("Restore of $label", JobKind.RESTORE, listOf(JobTask.RestoreApp(packageName, label, backup.dir.path, parts))),
        )
    }

    fun delete(backup: AppBackup) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { c.storage.delete(backup.dir) }
            _message.value = if (ok) "Backup deleted" else "Could not delete the backup completely"
            refresh()
        }
    }

    private fun shellAction(success: String, vararg argv: String) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching { c.shell.exec(*argv) }
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { r -> if (r.isSuccess && !r.output.contains("Failure") && !r.output.contains("Exception")) success else "Failed: ${r.output.take(200)}" },
                onFailure = { "Failed: ${it.message}" },
            )
            IconCache.invalidate(packageName)
            refresh()
        }
    }

    private val user get() = SettingsRepository.userId().toString()

    fun forceStop() = shellAction("App stopped", CMD, "activity", "force-stop", "--user", user, packageName)

    fun setEnabled(enabled: Boolean) = if (enabled) {
        shellAction("App enabled", CMD, "package", "enable", "--user", user, packageName)
    } else {
        shellAction("App disabled", CMD, "package", "disable-user", "--user", user, packageName)
    }

    fun clearData() = shellAction("App data cleared", CMD, "package", "clear", "--user", user, packageName)

    fun uninstall() = shellAction("App uninstalled", CMD, "package", "uninstall", "--user", user, packageName)

    companion object {
        private const val CMD = "/system/bin/cmd"
    }
}
