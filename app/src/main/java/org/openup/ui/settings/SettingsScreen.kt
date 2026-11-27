package org.openup.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openup.BuildConfig
import org.openup.container
import org.openup.core.io.Compression
import org.openup.data.SettingsRepository
import org.openup.data.ThemeMode
import org.openup.ui.components.ClickRow
import org.openup.ui.components.ConfirmDialog
import org.openup.ui.components.IntSliderRow
import org.openup.ui.components.SectionTitle
import org.openup.ui.components.SwitchRow
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val s by c.settings.state.collectAsStateWithLifecycle()
    val hasPassword by c.passwords.hasPassword.collectAsStateWithLifecycle()
    var folderDialog by remember { mutableStateOf(false) }
    var locationVersion by remember { mutableIntStateOf(0) }
    val location by produceState("…", s.backupDir, locationVersion) {
        value = withContext(Dispatchers.IO) { c.locations.describe() }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                c.locations.useExternal(uri)
                locationVersion++
            } catch (e: SecurityException) {
                Toast.makeText(context, "This location cannot be used for backups", Toast.LENGTH_LONG).show()
            }
        }
    }
    var passwordDialog by remember { mutableStateOf(false) }
    var removePassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Backup location")
            Text(
                location,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ClickRow(
                "Back up to a USB drive",
                "Pick the drive (or a folder on it) in the next screen. Backups go into an OpenUp folder on the drive.",
            ) { treePicker.launch(null) }
            ClickRow("Back up to this phone", "Choose a folder on internal storage") { folderDialog = true }
            Text(
                "Existing backups are not moved when you change the location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            IntSliderRow("Backups to keep per app", s.keepVersions, 1..SettingsRepository.MAX_VERSIONS, { "$it" }) { v ->
                c.settings.update { it.copy(keepVersions = v) }
            }

            SectionTitle("Compression")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.compression == Compression.ZSTD, onClick = { c.settings.update { it.copy(compression = Compression.ZSTD) } }, label = { Text("Zstandard") })
                FilterChip(selected = s.compression == Compression.NONE, onClick = { c.settings.update { it.copy(compression = Compression.NONE) } }, label = { Text("Off") })
            }
            if (s.compression == Compression.ZSTD) {
                IntSliderRow("Compression level", s.compressionLevel, 1..19, { level ->
                    when {
                        level <= 2 -> "$level (fastest)"
                        level <= 5 -> "$level (balanced)"
                        level <= 12 -> "$level (smaller)"
                        else -> "$level (smallest, slow)"
                    }
                }) { v -> c.settings.update { it.copy(compressionLevel = v) } }
            }
            Text(
                "APK files are already compressed and are stored as they are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle("Encryption")
            ClickRow(
                if (hasPassword) "Change backup password" else "Set backup password",
                if (hasPassword) "New backups are encrypted with AES-256-GCM" else "Backups are not encrypted",
            ) { passwordDialog = true }
            if (hasPassword) ClickRow("Remove password", "New backups will not be encrypted") { removePassword = true }
            SwitchRow(
                "Restore backups from older versions",
                "Allow encrypted backups made before OpenUp 1.1.2, which cannot be checked for changes. Leave off unless you need an old backup.",
                s.allowUnsignedBackups,
            ) { v -> c.settings.update { it.copy(allowUnsignedBackups = v) } }

            SectionTitle("Apps")
            SwitchRow("Skip cache folders", "Do not back up Android/data/<app>/cache", s.excludeCache) { v -> c.settings.update { it.copy(excludeCache = v) } }
            SwitchRow("Restore original installer", "Mark restored apps as installed by the store they came from (if present)", s.restoreInstaller) { v ->
                c.settings.update { it.copy(restoreInstaller = v) }
            }
            Text("Parts selected by default", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            SwitchRow("APK", null, s.parts.apk) { v -> c.settings.update { it.copy(parts = it.parts.copy(apk = v)) } }
            SwitchRow("External data", null, s.parts.extData) { v -> c.settings.update { it.copy(parts = it.parts.copy(extData = v)) } }
            SwitchRow("Expansion files (OBB)", null, s.parts.obb) { v -> c.settings.update { it.copy(parts = it.parts.copy(obb = v)) } }
            SwitchRow("Media", null, s.parts.media) { v -> c.settings.update { it.copy(parts = it.parts.copy(media = v)) } }
            SwitchRow("Permissions & app settings", null, s.parts.permissions) { v -> c.settings.update { it.copy(parts = it.parts.copy(permissions = v)) } }

            SectionTitle("Appearance")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = s.theme == m,
                        onClick = { c.settings.update { it.copy(theme = m) } },
                        label = { Text(m.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Dynamic colors", "Use colors from your wallpaper", s.dynamicColor) { v -> c.settings.update { it.copy(dynamicColor = v) } }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle("About")
            Text(
                "OpenUp ${BuildConfig.VERSION_NAME}\nFree and open source backup app powered by Shizuku. Licensed under the GNU GPL v3.\n" +
                    "OpenUp is an independent project and is not affiliated with Swift Backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                "Without root, Android does not let any app read other apps' private data (/data/data). " +
                    "OpenUp backs up everything that is reachable with Shizuku: APKs, external data, OBB, media, permissions and " +
                    "app settings, folders, messages, call logs, wallpapers and Wi-Fi networks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 24.dp),
            )
        }
    }

    if (folderDialog) {
        FolderDialog(
            current = if (c.locations.isExternal) SettingsRepository.defaultBackupDir() else s.backupDir,
            onSave = { path -> c.locations.useLocal(path) },
            onDismiss = { folderDialog = false },
        )
    }
    if (passwordDialog) {
        PasswordDialog(
            changing = hasPassword,
            onSave = { pw ->
                if (runCatching { c.passwords.setPassword(pw) }.isFailure) {
                    Toast.makeText(context, "Could not store the password securely on this device", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { passwordDialog = false },
        )
    }
    if (removePassword) {
        ConfirmDialog(
            "Remove password?",
            "New backups will no longer be encrypted. Existing encrypted backups can only be restored after setting the same password again.",
            "Remove",
            onConfirm = { c.passwords.clear() },
            onDismiss = { removePassword = false },
        )
    }
}

@Composable
private fun FolderDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val clean = text.trim().trimEnd('/')
    val error = when {
        !clean.startsWith("/storage/") -> "Use a folder on internal storage or an SD card (path starting with /storage/)"
        clean.split('/').any { it == ".." } -> "Invalid path"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "Existing backups are not moved automatically.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { text = SettingsRepository.defaultBackupDir() }) { Text("Use default") }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = {
                onSave(File(clean).path)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(changing: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val error = when {
        pw.length < 8 -> "At least 8 characters"
        pw != confirm -> "Passwords do not match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changing) "Change password" else "Set password") },
        text = {
            Column {
                Text(
                    "There is no way to recover a forgotten password." +
                        if (changing) " Backups made with the old password need the old password to be restored." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Repeat password") },
                    singleLine = true,
                    isError = pw.isNotEmpty() && confirm.isNotEmpty() && error != null,
                    supportingText = { if (pw.isNotEmpty()) Text(error ?: "") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = {
                onSave(pw)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
