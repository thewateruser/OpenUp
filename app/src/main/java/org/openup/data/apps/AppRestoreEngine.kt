package org.openup.data.apps

import android.content.Context
import android.os.Build
import org.openup.data.AppParts
import org.openup.data.BackupIO
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.model.AppBackup
import org.openup.data.model.DataPart
import org.openup.shizuku.ShellClient
import org.openup.shizuku.ShellException
import kotlinx.coroutines.CancellationException

class AppRestoreEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val apps: InstalledApps,
    private val shell: ShellClient,
) {
    suspend fun restore(backup: AppBackup, parts: AppParts, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        val meta = backup.meta
        val pkg = meta.packageName
        val warnings = ArrayList<String>()
        val done = ArrayList<String>()

        if (parts.apk && meta.apks.isNotEmpty()) {
            val installed = apps.get(pkg)
            when {
                pkg == context.packageName -> warnings.add("OpenUp cannot reinstall itself while running; APK skipped")
                installed == null -> {
                    install(backup, crypto, progress)
                    done.add("installed APK")
                }
                installed.versionCode < meta.versionCode -> {
                    // The app is present, so its data can still be restored if the update fails
                    // (e.g. because the installed copy is signed with a different key).
                    try {
                        install(backup, crypto, progress)
                        done.add("updated APK")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        warnings.add("APK not updated: ${e.message}")
                    }
                }
                installed.versionCode == meta.versionCode -> warnings.add("Same version already installed; APK not reinstalled")
                else -> warnings.add("A newer version (${installed.versionName}) is installed; APK not downgraded")
            }
        }

        val dataParts = listOfNotNull(
            meta.extData?.takeIf { parts.extData }?.let { Triple(it, AppPaths.extData(pkg), "external data") },
            meta.obb?.takeIf { parts.obb }?.let { Triple(it, AppPaths.obb(pkg), "expansion files") },
            meta.media?.takeIf { parts.media }?.let { Triple(it, AppPaths.media(pkg), "media") },
        )
        val installedNow = apps.get(pkg)
        if (dataParts.isNotEmpty()) {
            if (installedNow == null) {
                warnings.add("App is not installed, so its data was not restored")
            } else {
                if (pkg != context.packageName) {
                    shell.exec("/system/bin/cmd", "activity", "force-stop", "--user", SettingsRepository.userId().toString(), pkg)
                }
                for ((part, path, label) in dataParts) {
                    restoreTree(backup, part, path, label, crypto, progress, warnings)
                    done.add(label)
                }
            }
        }

        if (parts.permissions && meta.permissions.isNotEmpty() && installedNow != null) {
            val (granted, failed) = grantPermissions(pkg, meta.permissions)
            if (granted > 0) done.add("$granted permission(s)")
            if (failed > 0) warnings.add("$failed permission(s) could not be granted")
        }

        val summary = if (done.isEmpty()) "Nothing to restore for the selected parts" else "Restored " + done.joinToString(", ")
        return TaskOutcome(summary, warnings)
    }

    private suspend fun restoreTree(
        backup: AppBackup,
        part: DataPart,
        path: String,
        label: String,
        crypto: CryptoSession?,
        progress: ProgressSink,
        warnings: MutableList<String>,
    ) {
        progress.step("Restoring $label", part.stored.originalSize)
        val stats = shell.untar(path) { out ->
            BackupIO.open(backup.dir, part.stored, crypto?.key).use { input ->
                BackupIO.progressCopy(input, out, progress.asByteProgress())
            }
        }
        if (stats.skipped > 0) {
            warnings.add("$label: ${stats.skipped} item(s) could not be restored" +
                stats.skippedPaths.take(5).joinToString(prefix = " (", postfix = ")"))
        }
    }

    private suspend fun install(backup: AppBackup, crypto: CryptoSession?, progress: ProgressSink) {
        val meta = backup.meta
        val total = meta.apks.sumOf { it.stored.originalSize }
        progress.step("Installing APK", total)
        val args = arrayListOf(CMD, "package", "install-create", "-r", "--user", SettingsRepository.userId().toString(), "-S", total.toString())
        val installer = meta.installer
        if (settings.current.restoreInstaller && installer != null && installer != meta.packageName && apps.isInstalled(installer)) {
            args += listOf("-i", installer)
        }
        if (Build.VERSION.SDK_INT >= 34 && meta.targetSdk in 1..22) args += "--bypass-low-target-sdk-block"
        val create = shell.exec(*args.toTypedArray())
        val sessionId = SESSION_REGEX.find(create.stdout)?.groupValues?.get(1)
        if (!create.isSuccess || sessionId == null) {
            throw ShellException("Could not start installation: ${create.output.take(300)}")
        }
        try {
            meta.apks.forEachIndexed { index, apk ->
                val name = "${index}_" + apk.stored.file.substringAfterLast('/').removeSuffix(".enc")
                val write = shell.exec(
                    CMD, "package", "install-write", "-S", apk.stored.originalSize.toString(), sessionId, name, "-",
                    stdin = { out ->
                        BackupIO.open(backup.dir, apk.stored, crypto?.key).use { input ->
                            BackupIO.progressCopy(input, out, progress.asByteProgress())
                        }
                    },
                )
                if (!write.isSuccess || write.output.contains("Failure") || write.output.contains("Error:")) {
                    throw ShellException("Could not stage ${apk.split}: ${write.output.take(300)}")
                }
            }
            val commit = shell.exec(CMD, "package", "install-commit", sessionId)
            if (!commit.isSuccess || !commit.stdout.contains("Success")) {
                throw ShellException(explainInstallFailure(commit.output))
            }
        } catch (t: Throwable) {
            runCatching { shell.exec(CMD, "package", "install-abandon", sessionId) }
            throw t
        }
    }

    private suspend fun grantPermissions(pkg: String, permissions: List<String>): Pair<Int, Int> {
        val requested = apps.requestedPermissions(pkg)
        val already = apps.grantedRuntimePermissions(pkg).toSet()
        var granted = 0
        var failed = 0
        for (perm in permissions) {
            if (perm !in requested) continue
            if (perm in already) {
                granted++
                continue
            }
            val r = shell.exec(CMD, "package", "grant", "--user", SettingsRepository.userId().toString(), pkg, perm)
            if (r.isSuccess && !r.output.contains("Exception")) granted++ else failed++
        }
        return granted to failed
    }

    companion object {
        private const val CMD = "/system/bin/cmd"
        private val SESSION_REGEX = Regex("""\[(\d+)]""")

        private val FAILURES = mapOf(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "An installed version is signed with a different key. Uninstall it first.",
            "INSTALL_FAILED_VERSION_DOWNGRADE" to "A newer version is already installed.",
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "Not enough storage space to install.",
            "INSTALL_FAILED_OLDER_SDK" to "This app needs a newer Android version.",
            "INSTALL_FAILED_NO_MATCHING_ABIS" to "This app is not compatible with this device's processor.",
            "INSTALL_FAILED_MISSING_SPLIT" to "The backup is missing parts (split APKs) needed by this device.",
            "INSTALL_FAILED_DEPRECATED_SDK_VERSION" to "This app targets an Android version that is too old for this device.",
            "INSTALL_FAILED_INVALID_APK" to "The APK in the backup is invalid.",
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to "The APK in the backup is not signed correctly.",
            "INSTALL_FAILED_USER_RESTRICTED" to "Installing apps is restricted on this device.",
            "INSTALL_FAILED_VERIFICATION_FAILURE" to "Package verification rejected the app.",
        )

        fun explainInstallFailure(output: String): String {
            val code = FAILURES.keys.firstOrNull { output.contains(it) }
            val raw = output.trim().take(300)
            return if (code != null) "${FAILURES[code]} ($code)" else "Installation failed: $raw"
        }
    }
}
