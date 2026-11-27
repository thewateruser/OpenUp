package org.openup.jobs

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openup.container

/** Foreground service that keeps the process alive while a user started job runs. */
class BackupJobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (worker?.isActive == true) return START_NOT_STICKY
        val jobs = container.jobs
        val request = jobs.takePending()
        if (request == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        worker = scope.launch {
            val updater = launch {
                jobs.state.collect { s ->
                    if (s?.running == true) {
                        try {
                            NotificationManagerCompat.from(this@BackupJobService)
                                .notify(Notifications.ID_PROGRESS, Notifications.progress(this@BackupJobService, s))
                        } catch (_: SecurityException) {
                        }
                        // StateFlow is conflated, so pausing here simply skips intermediate states;
                        // Android rate-limits notification updates and once per second is plenty.
                        delay(1000)
                    }
                }
            }
            val result = try {
                jobs.run(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // run() reports task failures itself; this only guards against unexpected bugs.
                null
            } finally {
                // Release first: suspending calls in a cancelled coroutine would throw.
                jobs.release()
                withContext(NonCancellable) { updater.cancelAndJoin() }
            }
            withContext(Dispatchers.Main) {
                if (result != null) Notifications.showResult(this@BackupJobService, result)
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val notification = Notifications.progress(this, container.jobs.state.value)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.ID_PROGRESS, notification, type)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenUp:job").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopSelfSafely() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync services and crashes apps that do not stop within seconds.
        // Stop right away; onDestroy cancels the job coroutine.
        container.jobs.cancel()
        stopSelfSafely()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }
}
