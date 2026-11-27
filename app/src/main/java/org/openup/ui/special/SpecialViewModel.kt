package org.openup.ui.special

import android.Manifest
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openup.container
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialType
import org.openup.jobs.JobKind
import org.openup.jobs.JobRequest
import org.openup.jobs.JobTask
import org.openup.shizuku.ShizukuStatus

data class PermissionState(
    val ok: Boolean,
    val text: String,
    val runtime: List<String> = emptyList(),
    val canBackup: Boolean = ok,
    val canRestore: Boolean = ok,
)

class SpecialViewModel(app: Application, val type: SpecialType) : AndroidViewModel(app) {
    private val c = app.container
    val job = c.jobs.state
    val shizuku = c.shizuku.status
    val hasPassword = c.passwords.hasPassword

    private val _backups = MutableStateFlow<List<SpecialBackup>>(emptyList())
    val backups = _backups.asStateFlow()
    private val _permission = MutableStateFlow(PermissionState(true, ""))
    val permission = _permission.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        refresh()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun refresh() {
        _permission.value = computePermission()
        viewModelScope.launch {
            _backups.value = withContext(Dispatchers.IO) {
                if (c.privileges.hasStorageAccess()) c.storage.specialBackups(type) else emptyList()
            }
        }
    }

    private fun computePermission(): PermissionState {
        val p = c.privileges
        val ready = c.shizuku.status.value is ShizukuStatus.Ready
        return when (type) {
            SpecialType.MESSAGES -> {
                val read = p.has(Manifest.permission.READ_SMS)
                val write = p.canWriteSms()
                // null: this Android version has no restricted (end-to-end encrypted RCS) messages.
                val rcs = p.canReadRestrictedMessages()
                when {
                    !read -> PermissionState(false, "OpenUp needs access to your messages", listOf(Manifest.permission.READ_SMS))
                    !write || rcs == false -> PermissionState(
                        ok = false,
                        text = buildString {
                            append("Backing up works.")
                            if (rcs == false) append(" End-to-end encrypted RCS chats need Shizuku to be included.")
                            if (!write) append(" Restoring needs Shizuku to allow writing SMS.")
                        },
                        canBackup = true,
                        canRestore = write,
                    )
                    rcs == true -> PermissionState(true, "OpenUp can read and restore SMS, MMS and RCS, including end-to-end encrypted chats")
                    else -> PermissionState(true, "OpenUp can read and restore messages")
                }
            }
            SpecialType.CALL_LOGS -> if (p.hasCallLog()) {
                PermissionState(true, "OpenUp can read and restore call logs")
            } else {
                PermissionState(false, "OpenUp needs access to your call logs", listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG))
            }
            SpecialType.WALLPAPERS -> if (p.hasStorageAccess()) {
                PermissionState(true, "Static wallpapers are backed up as images. Live wallpapers are recorded by name only.")
            } else {
                PermissionState(false, "Storage access is needed (see the setup card on the home screen)")
            }
            SpecialType.WIFI -> if (ready) {
                PermissionState(true, "Saved networks including passwords. Enterprise (EAP) networks are not supported.")
            } else {
                PermissionState(false, "Wi-Fi backups need Shizuku to be running")
            }
        }
    }

    /** Grants permissions through Shizuku. Returns false when Shizuku is unavailable. */
    fun grantWithShizuku(): Boolean {
        if (c.shizuku.status.value !is ShizukuStatus.Ready) return false
        viewModelScope.launch {
            runCatching {
                when (type) {
                    SpecialType.MESSAGES -> {
                        c.privileges.grantSmsRead()
                        c.privileges.grantSmsWrite()
                        c.privileges.grantRestrictedMessages()
                    }
                    SpecialType.CALL_LOGS -> c.privileges.grantCallLog()
                    SpecialType.WALLPAPERS -> c.privileges.grantStorageAccess()
                    SpecialType.WIFI -> true
                }
            }.onFailure { _message.value = "Failed: ${it.message}" }
            refresh()
            if (!_permission.value.ok) _message.value = "Android did not accept the permission change"
        }
        return true
    }

    fun backup(): Boolean = c.jobs.start(getApplication(), JobRequest("${type.title} backup", JobKind.BACKUP, listOf(JobTask.BackupSpecial(type))))

    fun restore(b: SpecialBackup): Boolean =
        c.jobs.start(getApplication(), JobRequest("${type.title} restore", JobKind.RESTORE, listOf(JobTask.RestoreSpecial(type, b.dir.path))))

    fun delete(b: SpecialBackup) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { c.storage.delete(b.dir) }
            _message.value = if (ok) "Backup deleted" else "Could not delete the backup completely"
            refresh()
        }
    }
}
