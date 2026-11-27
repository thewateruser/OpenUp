package org.openup.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openup.data.model.SpecialType
import org.openup.jobs.JobState
import org.openup.shizuku.ShizukuManager
import org.openup.shizuku.ShizukuStatus
import org.openup.ui.components.formatRelative

@Composable
fun HomeScreen(
    onOpenApps: () -> Unit,
    onOpenSpecial: (SpecialType) -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolders: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val locationProblem by vm.locationProblem.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(shizuku) { vm.refresh() }
    LaunchedEffect(job?.running) { if (job?.running == false) vm.refresh() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val openBattery by vm.openBatterySettings.collectAsStateWithLifecycle()
    LaunchedEffect(openBattery) {
        if (openBattery) {
            vm.batterySettingsOpened()
            openBatterySettings(context)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenUp", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ShizukuCard(shizuku, onRequest = vm::requestShizukuPermission)
            }
            locationProblem?.let { problem ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Usb, null)
                            Spacer(Modifier.width(12.dp))
                            Text(problem, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            val missing = !setup.storage || !setup.notifications || !setup.battery
            if (missing) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SetupCard(
                        setup = setup,
                        storageText = if (vm.externalLocation) "Needed for wallpaper and folder backups" else "Needed to save backups in the OpenUp folder",
                        onStorage = {
                            if (!vm.grantStorage()) {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    openSettings(context, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                }
                            }
                        },
                        onNotifications = {
                            if (!vm.grantNotifications() && Build.VERSION.SDK_INT >= 33) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            }
                        },
                        onBattery = {
                            if (!vm.allowBackground()) openBatterySettings(context)
                        },
                    )
                }
            }
            job?.let { j ->
                item(span = { GridItemSpan(maxLineSpan) }) { JobCard(j, onOpenProgress) }
            }
            item {
                CategoryCard(
                    Icons.Outlined.Apps, "Apps",
                    if (counts.appBackups > 0) "${counts.appBackups} backed up" else "APK, external data, OBB, media",
                    onOpenApps,
                )
            }
            item { CategoryCard(Icons.Outlined.Sms, "Messages", specialText(counts, SpecialType.MESSAGES, "SMS and MMS")) { onOpenSpecial(SpecialType.MESSAGES) } }
            item { CategoryCard(Icons.Outlined.Call, "Call logs", specialText(counts, SpecialType.CALL_LOGS, "Call history")) { onOpenSpecial(SpecialType.CALL_LOGS) } }
            item {
                CategoryCard(
                    Icons.Outlined.Folder, "Folders",
                    if (counts.folders > 0) "${counts.folders} folder${if (counts.folders == 1) "" else "s"}" else "Any folder on your storage",
                    onOpenFolders,
                )
            }
            item { CategoryCard(Icons.Outlined.Wallpaper, "Wallpapers", specialText(counts, SpecialType.WALLPAPERS, "Home and lock screen")) { onOpenSpecial(SpecialType.WALLPAPERS) } }
            item { CategoryCard(Icons.Outlined.Wifi, "Wi-Fi", specialText(counts, SpecialType.WIFI, "Saved networks and passwords")) { onOpenSpecial(SpecialType.WIFI) } }
            item { CategoryCard(Icons.Outlined.Schedule, "Schedule", "Automatic backups", onOpenSchedule) }
            if (counts.lastBackupTime > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Last app backup ${formatRelative(counts.lastBackupTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

private fun specialText(counts: HomeCounts, type: SpecialType, fallback: String): String {
    val n = counts.specials[type] ?: 0
    return if (n > 0) "$n backup${if (n == 1) "" else "s"}" else fallback
}

@SuppressLint("BatteryLife")
private fun openBatterySettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun openSettings(context: android.content.Context, action: String) {
    val withPackage = Intent(action, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(withPackage) }.onFailure {
        runCatching { context.startActivity(Intent(action)) }
    }
}

@Composable
private fun ShizukuCard(status: ShizukuStatus, onRequest: () -> Unit) {
    val context = LocalContext.current
    val ready = status is ShizukuStatus.Ready
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null)
                Spacer(Modifier.width(12.dp))
                Text(
                    when (status) {
                        is ShizukuStatus.Ready -> "Shizuku connected (${status.modeName} mode)"
                        ShizukuStatus.NotInstalled -> "Shizuku is not installed"
                        ShizukuStatus.NotRunning -> "Shizuku is not running"
                        ShizukuStatus.Outdated -> "Shizuku is outdated"
                        ShizukuStatus.PermissionRequired -> "Shizuku permission needed"
                        ShizukuStatus.PermissionDenied -> "Shizuku permission denied"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            val explanation = when (status) {
                is ShizukuStatus.Ready -> "OpenUp can back up and restore apps, their external data, OBB and media, messages, call logs, wallpapers and Wi-Fi networks."
                ShizukuStatus.NotInstalled -> "OpenUp uses Shizuku to get the same privileges as adb, without root. Install Shizuku from F-Droid, GitHub or Google Play, then start it."
                ShizukuStatus.NotRunning -> "Open Shizuku and start it with Wireless debugging (Android 11+) or from a computer with adb. Shizuku has to be started again after every reboot."
                ShizukuStatus.Outdated -> "Please update Shizuku to the latest version."
                ShizukuStatus.PermissionRequired -> "Allow OpenUp to use Shizuku."
                ShizukuStatus.PermissionDenied -> "You denied the permission. Open Shizuku, find OpenUp in the list of authorized apps and allow it."
            }
            Spacer(Modifier.height(4.dp))
            Text(explanation, style = MaterialTheme.typography.bodyMedium)
            when (status) {
                ShizukuStatus.PermissionRequired -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequest) { Text("Grant permission") }
                }
                ShizukuStatus.NotRunning, ShizukuStatus.PermissionDenied, ShizukuStatus.Outdated -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)?.let {
                            runCatching { context.startActivity(it) }
                        }
                    }) { Text("Open Shizuku") }
                }
                ShizukuStatus.NotInstalled -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        }
                    }) { Text("Get Shizuku") }
                }
                is ShizukuStatus.Ready -> Unit
            }
        }
    }
}

@Composable
private fun SetupCard(setup: SetupState, storageText: String, onStorage: () -> Unit, onNotifications: () -> Unit, onBattery: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            if (!setup.storage) SetupRow(Icons.Outlined.Folder, "Storage access", storageText, "Allow", onStorage)
            if (!setup.notifications) SetupRow(Icons.Outlined.Notifications, "Notifications", "Show progress and results of backups", "Allow", onNotifications)
            if (!setup.battery) SetupRow(Icons.Outlined.BatteryAlert, "Background activity", "Lets scheduled backups run reliably", "Allow", onBattery)
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun JobCard(job: JobState, onOpen: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (job.running) "${job.title} in progress" else "${job.title} finished",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (job.running) {
                LinearProgressIndicator(progress = { job.overallProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${job.finished} of ${job.total} done", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(job.summaryText(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CategoryCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp).height(96.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
