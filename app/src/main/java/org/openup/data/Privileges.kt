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
    }
}
