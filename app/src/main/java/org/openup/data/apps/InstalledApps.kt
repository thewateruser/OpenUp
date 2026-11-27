package org.openup.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import org.openup.data.BackupIO
import java.io.File
import java.security.MessageDigest

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val systemApp: Boolean,
    val updatedSystemApp: Boolean,
    val enabled: Boolean,
    val apkPaths: List<Pair<String, String>>,
    val installer: String?,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val apkSize: Long,
) {
    val splitCount get() = apkPaths.size - 1
}

/** Queries the package manager for installed apps. */
class InstalledApps(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    @Suppress("DEPRECATION")
    fun packageInfo(packageName: String, flags: Int = 0): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun isInstalled(packageName: String) = packageInfo(packageName) != null

    @Suppress("DEPRECATION")
    fun list(): List<InstalledApp> {
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getInstalledPackages(0)
        }
        return packages.mapNotNull { toApp(it) }
    }

    fun get(packageName: String): InstalledApp? = packageInfo(packageName)?.let { toApp(it) }

    @Suppress("DEPRECATION")
    private fun toApp(pi: PackageInfo): InstalledApp? {
        val ai = pi.applicationInfo ?: return null
        val sourceDir = ai.sourceDir ?: return null
        val splits = ai.splitSourceDirs.orEmpty()
        val splitNames = ai.splitNames.orEmpty()
        val apkPaths = ArrayList<Pair<String, String>>()
        apkPaths.add("base" to sourceDir)
        for (i in splits.indices) apkPaths.add((splitNames.getOrNull(i) ?: "split$i") to splits[i])
        return InstalledApp(
            packageName = pi.packageName,
            label = try {
                ai.loadLabel(pm).toString()
            } catch (_: Exception) {
                pi.packageName
            },
            versionName = pi.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
            systemApp = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            updatedSystemApp = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            enabled = ai.enabled,
            apkPaths = apkPaths,
            installer = installerOf(pi.packageName),
            firstInstallTime = pi.firstInstallTime,
            lastUpdateTime = pi.lastUpdateTime,
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion,
            apkSize = apkPaths.sumOf { File(it.second).length() },
        )
    }

    @Suppress("DEPRECATION")
    fun installerOf(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }

    /** SHA-256 fingerprints of the app's signing certificates. */
    @Suppress("DEPRECATION")
    fun signingCertificates(packageName: String): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val pi = packageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES) ?: return emptyList()
            val info = pi.signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            packageInfo(packageName, PackageManager.GET_SIGNATURES)?.signatures
        } ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { BackupIO.hex(md.digest(it.toByteArray())) }
    }

    /** Runtime (dangerous) permissions that are currently granted to [packageName]. */
    @Suppress("DEPRECATION")
    fun grantedRuntimePermissions(packageName: String): List<String> {
        val pi = packageInfo(packageName, PackageManager.GET_PERMISSIONS) ?: return emptyList()
        val names = pi.requestedPermissions ?: return emptyList()
        val flags = pi.requestedPermissionsFlags ?: return emptyList()
        val result = ArrayList<String>()
        for (i in names.indices) {
            if (i >= flags.size || flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED == 0) continue
            val info = try {
                pm.getPermissionInfo(names[i], 0)
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
            val base = if (Build.VERSION.SDK_INT >= 28) info.protection else info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            if (base == PermissionInfo.PROTECTION_DANGEROUS) result.add(names[i])
        }
        return result
    }

    fun requestedPermissions(packageName: String): Set<String> =
        packageInfo(packageName, PackageManager.GET_PERMISSIONS)?.requestedPermissions?.toSet() ?: emptySet()
}
