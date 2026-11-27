package org.openup.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openup.data.SettingsRepository
import org.openup.data.model.FolderBackup
import org.openup.data.special.FolderEngine
import org.openup.shizuku.ShizukuStatus
import org.openup.ui.components.ConfirmDialog
import org.openup.ui.components.SectionTitle
import org.openup.ui.components.formatBytes
import org.openup.ui.components.formatDateTime
import org.openup.ui.components.formatRelative

@Composable
fun FoldersScreen(onBack: () -> Unit, onJobStarted: () -> Unit, vm: FoldersViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<FolderBackup?>(null) }
    var deleteTarget by remember { mutableStateOf<FolderBackup?>(null) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var busyNotice by remember { mutableIntStateOf(0) }
    val running = job?.running == true
    val ready = shizuku is ShizukuStatus.Ready

    LaunchedEffect(job?.running) { if (job?.running == false) vm.refresh() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(busyNotice) { if (busyNotice > 0) snackbar.showSnackbar("Another job is still running") }

    fun canRun(path: String) = !running && (ready || !FolderEngine.needsShell(path))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { picker = true }) { Icon(Icons.Outlined.CreateNewFolder, "Add folder") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Back up any folder on your phone or SD card – documents, downloads, camera photos, app folders and more. " +
                            "Restoring puts the files back where they were; files with the same name are overwritten and other files are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { picker = true }) { Text("Add folder") }
                        FilledTonalButton(
                            onClick = { if (!vm.backup(settings.folders.filter { canRun(it) })) busyNotice++ else onJobStarted() },
                            enabled = settings.folders.any { canRun(it) },
                        ) { Text("Back up all") }
                    }
                }
            }
            item { SectionTitle("Folders (${settings.folders.size})") }
            if (settings.folders.isEmpty()) {
                item { Text("No folders added yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(settings.folders, key = { "f:$it" }) { path ->
                val last = backups.firstOrNull { it.path == path }
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (last != null) "Last backup ${formatRelative(last.meta.backupTime)}" else "Not backed up yet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (FolderEngine.needsShell(path) && !ready) {
                                    Text("Needs Shizuku", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { removeTarget = path }) { Text("Remove") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { if (vm.backup(listOf(path))) onJobStarted() else busyNotice++ },
                                enabled = canRun(path),
                            ) { Text("Back up") }
                        }
                    }
                }
            }
            item { SectionTitle("Backups (${backups.size})") }
            if (backups.isEmpty()) {
                item { Text("No backups yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(backups, key = { "b:" + it.dir.path }) { b ->
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.path.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatDateTime(b.meta.backupTime)} · ${b.meta.summary} · ${formatBytes(context, b.meta.totalStoredSize)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (b.meta.encrypted) Icon(Icons.Outlined.Lock, "Encrypted", tint = MaterialTheme.colorScheme.primary)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { deleteTarget = b }) { Text("Delete") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { restoreTarget = b }, enabled = b.path?.let { canRun(it) } == true) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }

    if (picker) {
        FolderPickerDialog(
            start = "/storage/emulated/${SettingsRepository.userId()}",
            list = vm::listDirectories,
            onPick = { path ->
                val error = vm.add(path)
                if (error == null) picker = false
                error
            },
            onDismiss = { picker = false },
        )
    }
    restoreTarget?.let { b ->
        ConfirmDialog(
            "Restore folder?",
            "Files from the backup of ${formatDateTime(b.meta.backupTime)} are written to ${b.path}. " +
                "Files with the same name are overwritten; other files in the folder are kept.",
            "Restore",
            onConfirm = { if (vm.restore(b)) onJobStarted() else busyNotice++ },
            onDismiss = { restoreTarget = null },
        )
    }
    deleteTarget?.let { b ->
        ConfirmDialog(
            "Delete backup?",
            "The backup of ${b.path} from ${formatDateTime(b.meta.backupTime)} will be permanently deleted.",
            "Delete",
            onConfirm = { vm.delete(b) },
            onDismiss = { deleteTarget = null },
        )
    }
    removeTarget?.let { path ->
        ConfirmDialog(
            "Remove folder?",
            "$path will no longer be backed up. Existing backups are kept.",
            "Remove",
            onConfirm = { vm.remove(path) },
            onDismiss = { removeTarget = null },
        )
    }
}

/** Simple directory browser. [onPick] returns an error message, or null when the folder was accepted. */
@Composable
private fun FolderPickerDialog(
    start: String,
    list: suspend (String) -> List<String>?,
    onPick: (String) -> String?,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(start) }
    var children by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        loading = true
        children = list(path)
        loading = false
    }
    val canGoUp = path.count { it == '/' } > 1 // stay at or below /storage (SD cards live there)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (canGoUp) {
                        item {
                            PickerRow(Icons.Outlined.ArrowUpward, "..") {
                                path = path.substringBeforeLast('/')
                                error = null
                            }
                        }
                    }
                    when {
                        loading -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                        children == null -> item { Text("This folder cannot be read", color = MaterialTheme.colorScheme.error) }
                        children!!.isEmpty() -> item { Text("No sub-folders", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else -> items(children!!, key = { it }) { name ->
                            PickerRow(Icons.Outlined.Folder, name) {
                                path = "$path/$name"
                                error = null
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { error = onPick(path) }) { Text("Back up this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
