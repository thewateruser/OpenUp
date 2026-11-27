package org.openup.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed interface ShizukuStatus {
    /** The Shizuku app is not installed. */
    data object NotInstalled : ShizukuStatus

    /** Shizuku is installed but its service is not running. */
    data object NotRunning : ShizukuStatus

    /** Shizuku is older than API v11 and cannot be used. */
    data object Outdated : ShizukuStatus

    /** OpenUp still has to ask for Shizuku permission. */
    data object PermissionRequired : ShizukuStatus

    /** The user denied the permission and asked not to be asked again. */
    data object PermissionDenied : ShizukuStatus

    /** Ready. [uid] is 2000 for adb/shell mode or 0 when Shizuku runs as root. */
    data class Ready(val uid: Int) : ShizukuStatus {
        val modeName get() = if (uid == 0) "root" else "adb"
    }
}

class ShizukuUnavailableException(message: String) : Exception(message)

/** Tracks the Shizuku connection state for the whole app. */
class ShizukuManager(private val context: Context) {
    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.NotRunning)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        _status.value = compute()
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun compute(): ShizukuStatus {
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) return if (isShizukuInstalled()) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
        return try {
            if (Shizuku.isPreV11()) return ShizukuStatus.Outdated
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.Ready(Shizuku.getUid())
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                ShizukuStatus.PermissionDenied
            } else {
                ShizukuStatus.PermissionRequired
            }
        } catch (_: Throwable) {
            ShizukuStatus.NotRunning
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
        refresh()
    }

    /** Throws [ShizukuUnavailableException] with a user readable reason unless Shizuku is usable. */
    fun requireReady(): ShizukuStatus.Ready {
        refresh()
        return when (val s = _status.value) {
            is ShizukuStatus.Ready -> s
            ShizukuStatus.NotInstalled -> throw ShizukuUnavailableException("Shizuku is not installed")
            ShizukuStatus.NotRunning -> throw ShizukuUnavailableException("Shizuku is not running. Start it in the Shizuku app.")
            ShizukuStatus.Outdated -> throw ShizukuUnavailableException("Shizuku is too old. Please update it.")
            ShizukuStatus.PermissionRequired, ShizukuStatus.PermissionDenied ->
                throw ShizukuUnavailableException("OpenUp has not been granted Shizuku permission")
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
    }
}
