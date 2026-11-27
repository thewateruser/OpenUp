package org.openup.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.openup.R
import org.openup.ui.MainActivity

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_RESULTS = "results"
    const val ID_PROGRESS = 1001
    const val ID_RESULT = 1002
    const val ID_SCHEDULED = 1003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a backup or restore is running"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Backup results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Summary after a backup or restore finished"
            }
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_PROGRESS, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun progress(context: Context, state: JobState?): android.app.Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state == null) {
            b.setContentTitle("Preparing…").setProgress(0, 0, true)
        } else {
            val cur = state.current
            b.setContentTitle("${state.title} (${(state.finished + if (cur != null) 1 else 0).coerceAtMost(state.total)}/${state.total})")
            b.setContentText(listOfNotNull(cur?.task?.title, cur?.step?.takeIf { it.isNotEmpty() }).joinToString(" · "))
            b.setProgress(1000, (state.overallProgress * 1000).toInt(), false)
        }
        return b.build()
    }

    fun showResult(context: Context, state: JobState, id: Int = ID_RESULT) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = when {
            state.cancelled > 0 -> "${state.title} cancelled"
            state.failed > 0 -> "${state.title} finished with errors"
            else -> "${state.title} complete"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(state.summaryText())
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun showMessage(context: Context, title: String, text: String, id: Int = ID_SCHEDULED) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
