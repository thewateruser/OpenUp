package org.openup.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openup.container
import org.openup.data.ScheduleSettings
import org.openup.ui.components.SectionTitle
import org.openup.ui.components.SwitchRow

private val INTERVALS = listOf(6 to "6 hours", 12 to "12 hours", 24 to "Daily", 48 to "2 days", 72 to "3 days", 168 to "Weekly")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val s = settings.schedule
    val hasPassword by c.passwords.hasPassword.collectAsStateWithLifecycle()

    fun update(transform: (ScheduleSettings) -> ScheduleSettings) {
        c.settings.update { it.copy(schedule = transform(it.schedule)) }
        c.schedule.apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled backups") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SwitchRow("Automatic backups", "Run backups in the background on a schedule", s.enabled) { v -> update { it.copy(enabled = v) } }
            HorizontalDivider()
            SectionTitle("When")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVALS.forEach { (hours, label) ->
                    FilterChip(
                        selected = s.intervalHours == hours,
                        onClick = { update { it.copy(intervalHours = hours) } },
                        label = { Text(label) },
                        enabled = s.enabled,
                    )
                }
            }
            SwitchRow("Only while charging", null, s.requireCharging, enabled = s.enabled) { v -> update { it.copy(requireCharging = v) } }
            SectionTitle("What")
            SwitchRow("Apps", "Uses the default parts from Settings. Needs Shizuku to be running.", s.apps, enabled = s.enabled) { v -> update { it.copy(apps = v) } }
            SwitchRow("Only new or updated apps", "Skip apps whose version did not change since their last backup", s.onlyChangedApps, enabled = s.enabled && s.apps) { v -> update { it.copy(onlyChangedApps = v) } }
            SwitchRow("Include system apps", null, s.includeSystemApps, enabled = s.enabled && s.apps) { v -> update { it.copy(includeSystemApps = v) } }
            SwitchRow("Folders", "The folders added on the Folders screen", s.folders, enabled = s.enabled) { v -> update { it.copy(folders = v) } }
            SwitchRow("Messages", null, s.messages, enabled = s.enabled) { v -> update { it.copy(messages = v) } }
            SwitchRow("Call logs", null, s.callLogs, enabled = s.enabled) { v -> update { it.copy(callLogs = v) } }
            SwitchRow("Wallpapers", null, s.wallpapers, enabled = s.enabled) { v -> update { it.copy(wallpapers = v) } }
            SwitchRow("Wi-Fi networks", "Needs Shizuku to be running", s.wifi, enabled = s.enabled) { v -> update { it.copy(wifi = v) } }
            if (s.enabled && !hasPassword && (s.messages || s.callLogs || s.wifi)) {
                Text(
                    "No backup password is set: scheduled messages, call log and Wi-Fi backups are saved unencrypted. " +
                        "Set a password in Settings to encrypt them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                "Shizuku stops when the phone restarts, so app and Wi-Fi backups are skipped until you start it again. " +
                    "Allow background activity on the home screen so Android does not delay or stop scheduled backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
