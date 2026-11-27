package org.openup.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.openup.data.AppParts
import org.openup.shizuku.ShizukuStatus
import org.openup.ui.components.AppIcon
import org.openup.ui.components.EmptyState
import org.openup.ui.components.PartsDialog
import org.openup.ui.components.formatBytes
import org.openup.ui.components.formatRelative
import java.io.File

@Composable
fun AppsScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onJobStarted: () -> Unit,
    vm: AppsViewModel = viewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searching by rememberSaveable { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(job?.running) { if (job?.running == false) vm.reload() }

    fun startOrExplain(started: Boolean) {
        if (started) onJobStarted() else scope.launch {
            snackbar.showSnackbar(if (job?.running == true) "Another job is still running" else "Nothing to do for the selection")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            searching -> {
                                searching = false
                                vm.query.value = ""
                            }
                            selected.isNotEmpty() -> vm.clearSelection()
                            else -> onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { vm.query.value = it },
                            placeholder = { Text("Search apps") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (selected.isNotEmpty()) {
                        Text("${selected.size} selected")
                    } else {
                        Text("Apps")
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search") }
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, "Clear selection") }
                    }
                    IconButton(onClick = vm::selectAllVisible) { Icon(Icons.Outlined.SelectAll, "Select all") }
                    Box {
                        IconButton(onClick = { filterMenu = true }) { Icon(Icons.Outlined.FilterList, "Filter") }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            AppFilter.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.label) },
                                    leadingIcon = { RadioButton(selected = f == filter, onClick = null) },
                                    onClick = {
                                        vm.filter.value = f
                                        filterMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            AppSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    leadingIcon = { RadioButton(selected = s == sort, onClick = null) },
                                    onClick = {
                                        vm.sort.value = s
                                        sortMenu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = {
                                    sortMenu = false
                                    vm.reload()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                val (installedCount, backedUpCount) = vm.selectionInfo()
                BottomAppBar {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { dialog = "backup" },
                            enabled = installedCount > 0 && job?.running != true,
                            modifier = Modifier.weight(1f),
                        ) { Text("Back up ($installedCount)") }
                        FilledTonalButton(
                            onClick = { dialog = "restore" },
                            enabled = backedUpCount > 0 && job?.running != true,
                            modifier = Modifier.weight(1f),
                        ) { Text("Restore ($backedUpCount)") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (shizuku !is ShizukuStatus.Ready) {
                Text(
                    "Shizuku is not ready. You can browse apps, but backups and restores need Shizuku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                "${filter.label} · ${items.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> EmptyState(if (query.isNotEmpty()) "No apps match \"$query\"" else "No apps in this list")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(items, key = { it.packageName }) { item ->
                        AppRow(item, item.packageName in selected, onClick = { onOpenApp(item.packageName) }, onToggle = { vm.toggle(item.packageName) })
                    }
                }
            }
        }
    }

    when (dialog) {
        "backup" -> PartsDialog(
            title = "Back up apps",
            initial = vm.defaultParts,
            restore = false,
            confirm = "Back up",
            onConfirm = { parts: AppParts -> startOrExplain(vm.backupSelected(parts)) },
            onDismiss = { dialog = null },
        )
        "restore" -> PartsDialog(
            title = "Restore apps",
            initial = vm.defaultParts,
            restore = true,
            confirm = "Restore",
            onConfirm = { parts: AppParts -> startOrExplain(vm.restoreSelected(parts)) },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun AppRow(item: AppItem, checked: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(item.packageName, item.latest?.dir?.let { File(it, "icon.png") })
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val b = item.latest
            val status = when {
                b == null -> "Not backed up"
                item.installed == null -> "Not installed · backup ${formatRelative(b.meta.backupTime)}"
                item.outdated -> "Backup outdated · ${formatRelative(b.meta.backupTime)}"
                else -> "Backed up ${formatRelative(b.meta.backupTime)} · ${formatBytes(context, b.meta.totalStoredSize)}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b?.meta?.encrypted == true) {
                    Icon(Icons.Outlined.Lock, "Encrypted", Modifier.padding(end = 4.dp).width(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        b == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.outdated -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
