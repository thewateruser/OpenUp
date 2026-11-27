package org.openup.ui.apps

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import org.openup.data.AppParts
import org.openup.data.model.AppBackup
import org.openup.shizuku.ShizukuStatus
import org.openup.ui.components.AppIcon
import org.openup.ui.components.ConfirmDialog
import org.openup.ui.components.PartsDialog
import org.openup.ui.components.SectionTitle
import org.openup.ui.components.formatBytes
import org.openup.ui.components.formatDateTime
import org.openup.ui.components.formatRelative
import java.io.File

private sealed interface DetailDialog {
    data object Backup : DetailDialog
    data class Restore(val backup: AppBackup) : DetailDialog
    data class Delete(val backup: AppBackup) : DetailDialog
    data object ClearData : DetailDialog
    data object Uninstall : DetailDialog
    data object Disable : DetailDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(packageName: String, onBack: () -> Unit, onJobStarted: () -> Unit) {
    val context = LocalContext.current
    val vm: AppDetailViewModel = viewModel(key = "app-$packageName") {
        AppDetailViewModel(context.applicationContext as Application, packageName)
    }
    val installed by vm.installed.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val sizes by vm.sizes.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    var startFailed by remember { mutableIntStateOf(0) }

    LaunchedEffect(job?.running) { if (job?.running == false) vm.refresh() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(startFailed) {
        if (startFailed > 0) snackbar.showSnackbar("Another job is still running")
    }

    val ready = shizuku is ShizukuStatus.Ready
    val running = job?.running == true
    val label = installed?.label ?: backups.firstOrNull()?.meta?.label ?: packageName

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(packageName, backups.firstOrNull()?.dir?.let { File(it, "icon.png") }, size = 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(label, style = MaterialTheme.typography.titleLarge)
                        Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val i = installed
                        Text(
                            when {
                                i != null -> "Version ${i.versionName ?: "?"} (${i.versionCode})" +
                                    (if (i.systemApp) " · System app" else "") + (if (!i.enabled) " · Disabled" else "")
                                loaded -> "Not installed"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            installed?.let { i ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InfoLine("APK", formatBytes(context, i.apkSize) + if (i.splitCount > 0) " (${i.splitCount} splits)" else "")
                            val s = sizes
                            if (s != null) {
                                InfoLine("External data", if (s.extData < 0) "None" else formatBytes(context, s.extData))
                                InfoLine("Expansion (OBB)", if (s.obb < 0) "None" else formatBytes(context, s.obb))
                                InfoLine("Media", if (s.media < 0) "None" else formatBytes(context, s.media))
                            } else if (!ready) {
                                Text("Start Shizuku to see data sizes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            i.installer?.let { InfoLine("Installed by", it) }
                            InfoLine("Updated", formatDateTime(i.lastUpdateTime))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { dialog = DetailDialog.Backup },
                        enabled = installed != null && ready && !running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Back up") }
                    FilledTonalButton(
                        onClick = { backups.firstOrNull()?.let { dialog = DetailDialog.Restore(it) } },
                        enabled = backups.isNotEmpty() && ready && !running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Restore latest") }
                }
            }
            installed?.let { i ->
                item {
                    SectionTitle("Actions")
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (launch != null) AssistChip(onClick = { runCatching { context.startActivity(launch) } }, label = { Text("Launch") })
                        AssistChip(onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                            }
                        }, label = { Text("App info") })
                        if (ready && packageName != context.packageName) {
                            AssistChip(onClick = vm::forceStop, label = { Text("Force stop") })
                            if (i.enabled) {
                                AssistChip(onClick = { dialog = DetailDialog.Disable }, label = { Text("Disable") })
                            } else {
                                AssistChip(onClick = { vm.setEnabled(true) }, label = { Text("Enable") })
                            }
                            AssistChip(onClick = { dialog = DetailDialog.ClearData }, label = { Text("Clear data") })
                            if (!i.systemApp) {
                                AssistChip(onClick = { dialog = DetailDialog.Uninstall }, label = { Text("Uninstall") })
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Backups (${backups.size})") }
            if (backups.isEmpty()) {
                item {
                    Text(
                        if (loaded) "No backups yet" else "Loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(backups, key = { it.dir.path }) { b ->
                BackupCard(
                    b,
                    enabled = ready && !running,
                    onRestore = { dialog = DetailDialog.Restore(b) },
                    onDelete = { dialog = DetailDialog.Delete(b) },
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        DetailDialog.Backup -> PartsDialog(
            title = "Back up $label",
            initial = vm.defaultParts,
            restore = false,
            available = AppParts(
                apk = true,
                extData = sizes?.extData?.let { it >= 0 } ?: true,
                obb = sizes?.obb?.let { it >= 0 } ?: true,
                media = sizes?.media?.let { it >= 0 } ?: true,
                permissions = true,
            ),
            confirm = "Back up",
            onConfirm = { if (vm.backup(it)) onJobStarted() else startFailed++ },
            onDismiss = { dialog = null },
        )
        is DetailDialog.Restore -> PartsDialog(
            title = "Restore ${formatDateTime(d.backup.meta.backupTime)}",
            initial = vm.defaultParts,
            restore = true,
            available = AppParts(
                apk = d.backup.meta.apks.isNotEmpty(),
                extData = d.backup.meta.extData != null,
                obb = d.backup.meta.obb != null,
                media = d.backup.meta.media != null,
                permissions = d.backup.meta.hasPermissionsOrSettings,
            ),
            confirm = "Restore",
            onConfirm = { if (vm.restore(d.backup, it)) onJobStarted() else startFailed++ },
            onDismiss = { dialog = null },
        )
        is DetailDialog.Delete -> ConfirmDialog(
            "Delete backup?",
            "The backup from ${formatDateTime(d.backup.meta.backupTime)} will be permanently deleted.",
            "Delete",
            onConfirm = { vm.delete(d.backup) },
            onDismiss = { dialog = null },
        )
        DetailDialog.ClearData -> ConfirmDialog(
            "Clear app data?",
            "All data of $label will be deleted, including its private data that OpenUp cannot back up without root.",
            "Clear data",
            onConfirm = vm::clearData,
            onDismiss = { dialog = null },
        )
        DetailDialog.Uninstall -> ConfirmDialog(
            "Uninstall $label?",
            "Make sure you have a backup first. Private app data cannot be backed up without root and will be lost.",
            "Uninstall",
            onConfirm = vm::uninstall,
            onDismiss = { dialog = null },
        )
        DetailDialog.Disable -> ConfirmDialog(
            "Disable $label?",
            "The app will be hidden and cannot run until you enable it again.",
            "Disable",
            onConfirm = { vm.setEnabled(false) },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupCard(b: AppBackup, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val m = b.meta
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(m.backupTime), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatRelative(m.backupTime)} · v${m.versionName ?: "?"} (${m.versionCode}) · ${formatBytes(context, m.totalStoredSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (m.encrypted) Icon(Icons.Outlined.Lock, "Encrypted", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.apks.isNotEmpty()) PartChip(if (m.apks.size > 1) "APK +${m.apks.size - 1}" else "APK")
                m.extData?.let { PartChip("Data ${formatBytes(context, it.stored.originalSize)}") }
                m.obb?.let { PartChip("OBB ${formatBytes(context, it.stored.originalSize)}") }
                m.media?.let { PartChip("Media ${formatBytes(context, it.stored.originalSize)}") }
                if (m.permissions.isNotEmpty()) PartChip("${m.permissions.size} permissions")
                m.settings?.takeIf { !it.isEmpty }?.let { s ->
                    val n = s.appOps.size + s.accessibilityServices.size + if (s.batteryOptimizationExempt) 1 else 0
                    PartChip("$n app settings")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRestore, enabled = enabled) { Text("Restore") }
            }
        }
    }
}

@Composable
private fun PartChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
