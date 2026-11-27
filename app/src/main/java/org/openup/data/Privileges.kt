package org.openup.data

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.core.content.ContextCompat
import org.openup.shizuku.ShellClient

/**
 * Grants OpenUp the Android permissions it needs, using Shizuku instead of asking the user to
 * flip switches in system settings.
 */
class Privileges(private val context: Context, private val shell: ShellClient) {
    private val pkg get() = context.packageName
    private val user get() = SettingsRepository.userId().toString()

    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else has(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private suspend fun grant(permission: String): Boolean {
        if (has(permission)) return true
        shell.exec(CMD, "package", "grant", "--user", user, pkg, permission)
        return has(permission)
    }

    /** All-files access (Android 11+) or legacy storage permissions, plus image read for wallpapers. */
    suspend fun grantStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                shell.exec(CMD, "appops", "set", "--user", user, "--uid", pkg, "MANAGE_EXTERNAL_STORAGE", "allow")
            }
        } else {
            grant(Manifest.permission.READ_EXTERNAL_STORAGE)
            grant(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.READ_MEDIA_IMAGES)
        else if (Build.VERSION.SDK_INT >= 30) grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        return hasStorageAccess()
    }

    suspend fun grantNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.POST_NOTIFICATIONS) else true

    suspend fun grantCallLog(): Boolean =
        grant(Manifest.permission.READ_CALL_LOG) and grant(Manifest.permission.WRITE_CALL_LOG)

    fun hasCallLog() = has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.WRITE_CALL_LOG)

    suspend fun grantSmsRead(): Boolean = grant(Manifest.permission.READ_SMS)

    /** Lets OpenUp write to the SMS/MMS provider without becoming the default SMS app. */
    suspend fun grantSmsWrite(): Boolean {
        if (canWriteSms()) return true
        shell.exec(CMD, "appops", "set", "--user", user, pkg, "WRITE_SMS", "allow")
        return canWriteSms()
    }

    fun hasSms() = has(Manifest.permission.READ_SMS) && canWriteSms()

    /**
     * Android 17 marks end-to-end encrypted RCS messages as "restricted" in the message database;
     * only the default SMS app, or an app holding the READ_RESTRICTED_MESSAGES app-op, can read them.
     * Returns null when this Android version has no such op (nothing is restricted), otherwise
     * whether OpenUp may read restricted messages.
     */
    fun canReadRestrictedMessages(): Boolean? =
        opMode(OPSTR_READ_RESTRICTED_MESSAGES)?.let { it == AppOpsManager.MODE_ALLOWED }

    /** Lets OpenUp read encrypted RCS chats without becoming the default SMS app (Android 17+). */
    suspend fun grantRestrictedMessages(): Boolean {
        if (canReadRestrictedMessages() != false) return true
        shell.exec(CMD, "appops", "set", "--user", user, pkg, "READ_RESTRICTED_MESSAGES", "allow")
        if (canReadRestrictedMessages() == true) return true
        // Fall back to a uid-wide mode in case the platform checks the op by uid.
        shell.exec(CMD, "appops", "set", "--user", user, "--uid", pkg, "READ_RESTRICTED_MESSAGES", "allow")
        return canReadRestrictedMessages() == true
    }

    /** Mode of [op] for OpenUp, or null if this Android version does not know the op. */
    @Suppress("DEPRECATION")
    private fun opMode(op: String): Int? {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(op, Process.myUid(), pkg)
            } else {
                ops.checkOpNoThrow(op, Process.myUid(), pkg)
            }
        } catch (_: IllegalArgumentException) {
            null // unknown op on this release
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun canWriteSms(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(OPSTR_WRITE_SMS, Process.myUid(), pkg)
            } else {
                ops.checkOpNoThrow(OPSTR_WRITE_SMS, Process.myUid(), pkg)
            }
        } catch (_: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val CMD = "/system/bin/cmd"
        private const val OPSTR_WRITE_SMS = "android:write_sms"
        private const val OPSTR_READ_RESTRICTED_MESSAGES = "android:read_restricted_messages"
    }
}
