package org.openup.jobs

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import org.openup.container
import org.openup.data.model.SpecialType
import org.openup.shizuku.ShizukuStatus

/** Periodic automatic backup, configured on the Schedule screen. */
class ScheduledBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(state: JobState?): ForegroundInfo {
        val n = Notifications.progress(applicationContext, state, idleTitle = "Scheduled backup running")
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.ID_PROGRESS + 10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_PROGRESS + 10, n)
        }
    }

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current.schedule
        if (!s.enabled) return Result.success()
        if (!c.jobs.tryReserve()) return Result.retry()
        try {
            return runScheduled(c, s)
        } finally {
            c.jobs.release()
        }
    }

    private suspend fun runScheduled(c: org.openup.AppContainer, s: org.openup.data.ScheduleSettings): Result {

        c.shizuku.refresh()
        val shizukuReady = c.shizuku.status.value is ShizukuStatus.Ready
        val notes = ArrayList<String>()
        val tasks = ArrayList<JobTask>()

        if (s.apps) {
            if (shizukuReady) {
                val latest = c.storage.latestAppBackups()
                val parts = c.settings.current.parts
                c.installedApps.list()
                    .filter { s.includeSystemApps || !it.systemApp }
                    .filter { app ->
                        if (!s.onlyChangedApps) return@filter true
                        val last = latest[app.packageName]?.meta ?: return@filter true
                        last.versionCode != app.versionCode || app.lastUpdateTime > last.backupTime
                    }
                    // Oldest backups first, so an interrupted run still makes progress next time.
                    .sortedBy { latest[it.packageName]?.meta?.backupTime ?: 0L }
                    .forEach { tasks.add(JobTask.BackupApp(it.packageName, it.label, parts)) }
            } else {
                notes.add("App backups were skipped because Shizuku is not running.")
            }
        }
        if (s.folders) c.settings.current.folders.forEach { tasks.add(JobTask.BackupFolder(it)) }
        if (s.messages) tasks.add(JobTask.BackupSpecial(SpecialType.MESSAGES))
        if (s.callLogs) tasks.add(JobTask.BackupSpecial(SpecialType.CALL_LOGS))
        if (s.wallpapers) tasks.add(JobTask.BackupSpecial(SpecialType.WALLPAPERS))
        if (s.wifi) {
            if (shizukuReady) tasks.add(JobTask.BackupSpecial(SpecialType.WIFI))
            else notes.add("Wi-Fi backup was skipped because Shizuku is not running.")
        }

        if (tasks.isEmpty()) {
            if (notes.isNotEmpty()) Notifications.showMessage(applicationContext, "Scheduled backup", notes.joinToString("\n"))
            return Result.success()
        }

        // Long backups need a foreground service. Android may refuse that while OpenUp is in the
        // background (unless battery optimisation is disabled); the job then runs best-effort.
        try {
            setForeground(foregroundInfo(null))
        } catch (_: Exception) {
        }

        val result = c.jobs.run(JobRequest("Scheduled backup", JobKind.BACKUP, tasks, scheduled = true))
        val text = buildString {
            append(result.summaryText())
            notes.forEach { append("\n").append(it) }
            result.tasks.filter { it.status == TaskStatus.FAILED }.take(5).forEach {
                append("\n• ${it.task.title}: ${it.message}")
            }
        }
        Notifications.showMessage(applicationContext, "Scheduled backup finished", text)
        return Result.success()
    }
}
