package org.openup.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.openup.data.SettingsRepository
import java.util.concurrent.TimeUnit

/** Keeps the WorkManager schedule in sync with the schedule settings. */
class ScheduleManager(private val context: Context, private val settings: SettingsRepository) {
    fun apply() {
        val s = settings.current.schedule
        val wm = WorkManager.getInstance(context)
        if (!s.enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(s.requireCharging)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(s.intervalHours.toLong().coerceAtLeast(1), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "scheduled-backup"
    }
}
