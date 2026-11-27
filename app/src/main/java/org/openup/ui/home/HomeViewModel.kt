package org.openup.ui.home

import android.app.Application
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openup.container
import org.openup.data.model.SpecialType
import org.openup.shizuku.ShizukuStatus

data class SetupState(
    val storage: Boolean = true,
    val notifications: Boolean = true,
    val battery: Boolean = true,
)

data class HomeCounts(
    val appBackups: Int = 0,
    val specials: Map<SpecialType, Int> = emptyMap(),
    val lastBackupTime: Long = 0,
    val folders: Int = 0,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val shizuku = c.shizuku.status
    val job = c.jobs.state

    private val _setup = MutableStateFlow(SetupState())
    val setup = _setup.asStateFlow()
    private val _counts = MutableStateFlow(HomeCounts())
    val counts = _counts.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun refresh() {
        c.shizuku.refresh()
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(PowerManager::class.java)
        _setup.value = SetupState(
            storage = c.privileges.hasStorageAccess(),
            notifications = Build.VERSION.SDK_INT < 33 || c.privileges.has(android.Manifest.permission.POST_NOTIFICATIONS),
            battery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true,
        )
        viewModelScope.launch {
            _counts.value = withContext(Dispatchers.IO) {
                val folders = c.settings.current.folders.size
                if (!c.privileges.hasStorageAccess()) return@withContext HomeCounts(folders = folders)
                val latest = c.storage.latestAppBackups()
                val specials = SpecialType.entries.associateWith { c.storage.specialBackups(it).size }
                HomeCounts(latest.size, specials, latest.values.maxOfOrNull { it.meta.backupTime } ?: 0, folders)
            }
        }
    }

    fun requestShizukuPermission() = c.shizuku.requestPermission()

    private fun ready() = c.shizuku.status.value is ShizukuStatus.Ready

    /** Returns false if Shizuku is not available, so the UI can fall back to system screens. */
    fun grantStorage(): Boolean {
        if (!ready()) return false
        viewModelScope.launch {
            val ok = runCatching { c.privileges.grantStorageAccess() }.getOrDefault(false)
            if (!ok) _message.value = "Could not grant storage access through Shizuku"
            refresh()
        }
        return true
    }

    fun grantNotifications(): Boolean {
        if (!ready()) return false
        viewModelScope.launch {
            runCatching { c.privileges.grantNotifications() }
            refresh()
        }
        return true
    }

    private val _openBatterySettings = MutableStateFlow(false)
    val openBatterySettings = _openBatterySettings.asStateFlow()

    fun batterySettingsOpened() {
        _openBatterySettings.value = false
    }

    fun allowBackground(): Boolean {
        if (!ready()) return false
        viewModelScope.launch {
            runCatching {
                c.shell.exec("/system/bin/cmd", "deviceidle", "whitelist", "+" + getApplication<Application>().packageName)
            }
            refresh()
            // Fall back to the system dialog if the shell command was not accepted.
            if (!_setup.value.battery) _openBatterySettings.value = true
        }
        return true
    }
}
