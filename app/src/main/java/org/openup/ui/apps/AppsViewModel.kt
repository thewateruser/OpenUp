package org.openup.ui.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openup.container
import org.openup.data.AppParts
import org.openup.data.apps.InstalledApp
import org.openup.data.model.AppBackup
import org.openup.jobs.JobKind
import org.openup.jobs.JobRequest
import org.openup.jobs.JobTask

data class AppItem(
    val packageName: String,
    val label: String,
    val installed: InstalledApp?,
    val latest: AppBackup?,
) {
    val isSystem get() = installed?.systemApp ?: latest?.meta?.systemApp ?: false
    val hasBackup get() = latest != null
    val outdated: Boolean
        get() {
            val i = installed ?: return false
            val b = latest?.meta ?: return false
            return i.versionCode != b.versionCode || i.lastUpdateTime > b.backupTime
        }
}

enum class AppFilter(val label: String) {
    USER("User apps"),
    SYSTEM("System apps"),
    ALL("All apps"),
    BACKED_UP("Backed up"),
    NOT_BACKED_UP("Not backed up"),
    OUTDATED("Backup outdated"),
    NOT_INSTALLED("Not installed (backup only)"),
}

enum class AppSort(val label: String) { NAME("Name"), LAST_BACKUP("Last backup"), SIZE("Backup size"), UPDATED("Last updated") }

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    private val _all = MutableStateFlow<List<AppItem>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(AppFilter.USER)
    val sort = MutableStateFlow(AppSort.NAME)
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()
    val job = c.jobs.state
    val shizuku = c.shizuku.status
    val defaultParts get() = c.settings.current.parts

    val items: StateFlow<List<AppItem>> = combine(_all, query, filter, sort) { all, q, f, s ->
        val needle = q.trim().lowercase()
        all.asSequence()
            .filter {
                when (f) {
                    AppFilter.USER -> it.installed != null && !it.isSystem
                    AppFilter.SYSTEM -> it.installed != null && it.isSystem
                    AppFilter.ALL -> it.installed != null
                    AppFilter.BACKED_UP -> it.hasBackup
                    AppFilter.NOT_BACKED_UP -> it.installed != null && !it.hasBackup
                    AppFilter.OUTDATED -> it.outdated
                    AppFilter.NOT_INSTALLED -> it.installed == null && it.hasBackup
                }
            }
            .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle) }
            .sortedWith(
                when (s) {
                    AppSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    AppSort.LAST_BACKUP -> compareByDescending<AppItem> { it.latest?.meta?.backupTime ?: 0L }.thenBy { it.label.lowercase() }
                    AppSort.SIZE -> compareByDescending<AppItem> { it.latest?.meta?.totalStoredSize ?: 0L }.thenBy { it.label.lowercase() }
                    AppSort.UPDATED -> compareByDescending<AppItem> { it.installed?.lastUpdateTime ?: 0L }.thenBy { it.label.lowercase() }
                }
            )
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            val list = withContext(Dispatchers.IO) {
                val installed = c.installedApps.list().associateBy { it.packageName }
                val backups = if (c.privileges.hasStorageAccess()) c.storage.latestAppBackups() else emptyMap()
                val pkgs = installed.keys + backups.keys
                pkgs.map { pkg ->
                    val i = installed[pkg]
                    val b = backups[pkg]
                    AppItem(pkg, i?.label ?: b?.meta?.label ?: pkg, i, b)
                }
            }
            _all.value = list
            _selected.value = _selected.value.filter { s -> list.any { it.packageName == s } }.toSet()
            _loading.value = false
        }
    }

    fun toggle(pkg: String) {
        _selected.value = _selected.value.let { if (pkg in it) it - pkg else it + pkg }
    }

    fun selectAllVisible() {
        _selected.value = _selected.value + items.value.map { it.packageName }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Starts a backup of the selected installed apps. */
    fun backupSelected(parts: AppParts): Boolean {
        val byPkg = _all.value.associateBy { it.packageName }
        val tasks = _selected.value.mapNotNull { byPkg[it] }.filter { it.installed != null }
            .sortedBy { it.label.lowercase() }
            .map { JobTask.BackupApp(it.packageName, it.label, parts) }
        if (tasks.isEmpty()) return false
        val ok = c.jobs.start(getApplication(), JobRequest("Backup of ${tasks.size} app${if (tasks.size == 1) "" else "s"}", JobKind.BACKUP, tasks))
        if (ok) clearSelection()
        return ok
    }

    /** Restores the newest backup of every selected app that has one. */
    fun restoreSelected(parts: AppParts): Boolean {
        val byPkg = _all.value.associateBy { it.packageName }
        val tasks = _selected.value.mapNotNull { byPkg[it] }.filter { it.latest != null }
            .sortedBy { it.label.lowercase() }
            .map { JobTask.RestoreApp(it.packageName, it.label, it.latest!!.dir.path, parts) }
        if (tasks.isEmpty()) return false
        val ok = c.jobs.start(getApplication(), JobRequest("Restore of ${tasks.size} app${if (tasks.size == 1) "" else "s"}", JobKind.RESTORE, tasks))
        if (ok) clearSelection()
        return ok
    }

    fun selectionInfo(): Pair<Int, Int> {
        val byPkg = _all.value.associateBy { it.packageName }
        val sel = _selected.value.mapNotNull { byPkg[it] }
        return sel.count { it.installed != null } to sel.count { it.latest != null }
    }
}
