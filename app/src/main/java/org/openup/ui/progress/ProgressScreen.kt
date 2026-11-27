package org.openup.ui.progress

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openup.container
import org.openup.jobs.TaskState
import org.openup.jobs.TaskStatus
import org.openup.ui.components.EmptyState

@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val jobs = LocalContext.current.container.jobs
    val state by jobs.state.collectAsStateWithLifecycle()
    val job = state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.title ?: "Progress") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (job == null) {
            EmptyState("No backup or restore has run yet", Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                LinearProgressIndicator(progress = { job.overallProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    if (job.running) {
                        if (job.cancelRequested) "Cancelling…" else "${job.finished} of ${job.total} done"
                    } else {
                        job.summaryText()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (job.running) {
                        OutlinedButton(onClick = jobs::cancel, enabled = !job.cancelRequested) { Text("Cancel") }
                    } else {
                        Button(onClick = {
                            jobs.clearFinished()
                            onBack()
                        }) { Text("Done") }
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(job.tasks, key = { i, _ -> i }) { _, t -> TaskRow(t) }
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskState) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = t.warnings.isNotEmpty() || t.message.length > 60
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        val (icon, tint) = when (t.status) {
            TaskStatus.PENDING -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.outline
            TaskStatus.RUNNING -> null to MaterialTheme.colorScheme.primary
            TaskStatus.DONE -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
            TaskStatus.WARNING -> Icons.Outlined.Warning to MaterialTheme.colorScheme.tertiary
            TaskStatus.FAILED -> Icons.Outlined.Error to MaterialTheme.colorScheme.error
            TaskStatus.CANCELLED -> Icons.Outlined.Block to MaterialTheme.colorScheme.outline
        }
        if (icon == null) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, t.status.name, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(t.task.title, style = MaterialTheme.typography.bodyLarge)
            when (t.status) {
                TaskStatus.RUNNING -> {
                    if (t.step.isNotEmpty()) Text(t.step, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    if (t.progress >= 0) {
                        LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                else -> if (t.message.isNotEmpty()) {
                    Text(
                        t.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                    )
                }
            }
            if (t.warnings.isNotEmpty()) {
                if (expanded) {
                    t.warnings.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                } else {
                    Text(
                        "${t.warnings.size} warning${if (t.warnings.size == 1) "" else "s"} – tap to show",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
