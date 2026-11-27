package org.openup.ui.special

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialType
import org.openup.data.model.StoredFile
import org.openup.shizuku.ShizukuStatus
import org.openup.ui.components.ConfirmDialog
import org.openup.ui.components.SectionTitle
import org.openup.ui.components.formatBytes
import org.openup.ui.components.formatDateTime
import org.openup.ui.components.formatRelative
import org.openup.data.location.StorageNode
import org.openup.data.location.resolve

private fun description(type: SpecialType) = when (type) {
    SpecialType.MESSAGES -> "Backs up all SMS, MMS (including pictures and other attachments) and RCS chats – also " +
        "end-to-end encrypted RCS chats on Android 17, through Shizuku, without making OpenUp the default SMS app. " +
        "Restoring skips messages that already exist, so it is safe to restore more than once."
    SpecialType.CALL_LOGS -> "Backs up your call history. Restoring skips calls that already exist."
    SpecialType.WALLPAPERS -> "Backs up the home and lock screen wallpapers."
    SpecialType.WIFI -> "Backs up saved Wi-Fi networks with their passwords using Shizuku – no root needed."
}

@Composable
fun SpecialScreen(type: SpecialType, onBack: () -> Unit, onJobStarted: () -> Unit) {
    val context = LocalContext.current
    val vm: SpecialViewModel = viewModel(key = "special-$type") {
        SpecialViewModel(context.applicationContext as Application, type)
    }
    val backups by vm.backups.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val hasPassword by vm.hasPassword.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<SpecialBackup?>(null) }
    var deleteTarget by remember { mutableStateOf<SpecialBackup?>(null) }
    var busyNotice by remember { mutableIntStateOf(0) }
    var unencryptedWarning by remember { mutableStateOf(false) }
    val sensitive = type == SpecialType.MESSAGES || type == SpecialType.CALL_LOGS || type == SpecialType.WIFI
    val startBackup: () -> Unit = { if (vm.backup()) onJobStarted() else busyNotice++ }

    LifecycleResumeEffect(type) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(job?.running) { if (job?.running == false) vm.refresh() }
    LaunchedEffect(shizuku) { vm.refresh() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(busyNotice) { if (busyNotice > 0) snackbar.showSnackbar("Another job is still running") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val running = job?.running == true
    val needsShizuku = type == SpecialType.WIFI
    val shizukuOk = !needsShizuku || shizuku is ShizukuStatus.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(type.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(description(type), style = MaterialTheme.typography.bodyMedium)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (permission.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(permission.text, style = MaterialTheme.typography.bodyMedium)
                            if (!permission.ok && type != SpecialType.WIFI) {
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = {
                                    if (!vm.grantWithShizuku() && permission.runtime.isNotEmpty()) {
                                        launcher.launch(permission.runtime.toTypedArray())
                                    }
                                }) { Text("Grant access") }
                            }
                        }
                    }
                    if (sensitive && !hasPassword) {
                        Text(
                            "Tip: set a backup password in Settings so this backup is encrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Button(
                        onClick = { if (sensitive && !hasPassword) unencryptedWarning = true else startBackup() },
                        enabled = !running && shizukuOk && permission.canBackup,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Back up now") }
                }
            }
            item { SectionTitle("Backups (${backups.size})") }
            if (backups.isEmpty()) {
                item {
                    Text("No backups yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(backups, key = { it.dir.id }) { b ->
                SpecialBackupCard(
                    b,
                    restoreEnabled = !running && shizukuOk && permission.canRestore,
                    onRestore = { restoreTarget = b },
                    onDelete = { deleteTarget = b },
                )
            }
        }
    }

    if (unencryptedWarning) {
        val what = when (type) {
            SpecialType.MESSAGES -> "your messages"
            SpecialType.CALL_LOGS -> "your call history"
            else -> "your Wi-Fi passwords"
        }
        ConfirmDialog(
            "Back up without encryption?",
            "No backup password is set, so $what will be saved unencrypted. Other apps that can access all files " +
                "on this phone (or anyone with the USB drive) could read them. Set a password in Settings to encrypt backups.",
            "Back up anyway",
            onConfirm = startBackup,
            onDismiss = { unencryptedWarning = false },
        )
    }
    restoreTarget?.let { b ->
        ConfirmDialog(
            "Restore ${type.title.lowercase()}?",
            "Restore the backup from ${formatDateTime(b.meta.backupTime)} (${b.meta.summary})." +
                if (type == SpecialType.WALLPAPERS) " Your current wallpapers will be replaced." else "",
            "Restore",
            onConfirm = { if (vm.restore(b)) onJobStarted() else busyNotice++ },
            onDismiss = { restoreTarget = null },
        )
    }
    deleteTarget?.let { b ->
        ConfirmDialog(
            "Delete backup?",
            "The backup from ${formatDateTime(b.meta.backupTime)} will be permanently deleted.",
            "Delete",
            onConfirm = { vm.delete(b) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SpecialBackupCard(b: SpecialBackup, restoreEnabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(b.meta.backupTime), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatRelative(b.meta.backupTime)} · ${b.meta.summary} · ${formatBytes(context, b.meta.totalStoredSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (b.meta.encrypted) Icon(Icons.Outlined.Lock, "Encrypted", tint = MaterialTheme.colorScheme.primary)
            }
            if (b.type == SpecialType.WALLPAPERS && !b.meta.encrypted) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.meta.files.forEach { WallpaperThumb(b.dir, it) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRestore, enabled = restoreEnabled) { Text("Restore") }
            }
        }
    }
}

@Composable
private fun WallpaperThumb(dir: StorageNode, file: StoredFile) {
    val image by produceState<ImageBitmap?>(null, dir.id, file.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (file.parts != 1) return@runCatching null
                val node = dir.resolve(file.file)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                node.openInput().use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 240 && bounds.outHeight / (sample * 2) >= 240) sample *= 2
                node.openInput().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        image?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 128.dp).clip(RoundedCornerShape(8.dp)))
        }
        Text(if (file.file.startsWith("lock")) "Lock" else "Home", style = MaterialTheme.typography.labelSmall)
    }
}
