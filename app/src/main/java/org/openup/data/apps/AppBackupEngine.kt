package org.openup.data.apps

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import org.openup.core.io.Compression
import org.openup.core.io.Codec
import org.openup.data.AppParts
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.model.ApkFile
import org.openup.data.model.AppBackupMeta
import org.openup.data.model.DataPart
import org.openup.shizuku.ShellClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Paths of the per-app folders on shared storage that the shell user can access. */
object AppPaths {
    private fun base() = "/storage/emulated/${SettingsRepository.userId()}/Android"
    fun extData(pkg: String) = "${base()}/data/$pkg"
    fun obb(pkg: String) = "${base()}/obb/$pkg"
    fun media(pkg: String) = "${base()}/media/$pkg"
}

class AppBackupEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val storage: BackupStorage,
    private val apps: InstalledApps,
    private val shell: ShellClient,
) {
    suspend fun backup(packageName: String, parts: AppParts, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        val app = apps.get(packageName) ?: throw IOException("App is not installed")
        val s = settings.current
        storage.ensureRoot()
        val (id, partial) = storage.newPartial(storage.appDir(packageName))
        val warnings = ArrayList<String>()
        try {
            val apks = if (parts.apk) backupApks(app, partial, crypto, progress) else emptyList()
            val excludes = if (s.excludeCache) listOf("cache", "code_cache") else emptyList()
            val ext = if (parts.extData) backupTree(AppPaths.extData(packageName), "extdata", excludes, partial, crypto, progress, warnings, "external data") else null
            val obb = if (parts.obb) backupTree(AppPaths.obb(packageName), "obb", emptyList(), partial, crypto, progress, warnings, "expansion files") else null
            val media = if (parts.media) backupTree(AppPaths.media(packageName), "media", emptyList(), partial, crypto, progress, warnings, "media") else null
            if (apks.isEmpty() && ext == null && obb == null && media == null) {
                throw IOException("Nothing to back up: the selected parts do not exist for this app")
            }
            saveIcon(packageName, File(partial, BackupStorage.ICON))
            val meta = AppBackupMeta(
                packageName = packageName,
                label = app.label,
                versionName = app.versionName,
                versionCode = app.versionCode,
                minSdk = app.minSdk,
                targetSdk = app.targetSdk,
                systemApp = app.systemApp,
                installer = app.installer,
                signingCertSha256 = apps.signingCertificates(packageName),
                backupTime = System.currentTimeMillis(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidSdk = Build.VERSION.SDK_INT,
                apks = apks,
                extData = ext,
                obb = obb,
                media = media,
                permissions = if (parts.permissions) apps.grantedRuntimePermissions(packageName) else emptyList(),
            )
            storage.writeAppMeta(partial, meta)
            storage.commitPartial(partial, id)
            storage.prune(storage.appDir(packageName), s.keepVersions)
            val what = buildList {
                if (apks.isNotEmpty()) add(if (apks.size > 1) "APK (+${apks.size - 1} splits)" else "APK")
                if (ext != null) add("data")
                if (obb != null) add("OBB")
                if (media != null) add("media")
            }.joinToString(", ")
            return TaskOutcome("Backed up $what", warnings)
        } catch (t: Throwable) {
            partial.deleteRecursively()
            throw t
        }
    }

    private fun backupApks(app: InstalledApp, dir: File, crypto: CryptoSession?, progress: ProgressSink): List<ApkFile> {
        val apkDir = File(dir, "apk")
        if (!apkDir.mkdirs()) throw IOException("Cannot create $apkDir")
        progress.step("Backing up APK", app.apkSize)
        val used = HashSet<String>()
        return app.apkPaths.map { (split, path) ->
            val src = File(path)
            if (!src.canRead()) throw IOException("Cannot read ${src.name} of ${app.packageName}")
            var base = if (split == "base") "base" else "split_" + split.replace(Regex("[^A-Za-z0-9._-]"), "_")
            while (!used.add(base)) base += "_"
            val target = File(apkDir, Codec.fileName("$base.apk", Compression.NONE, crypto != null))
            val stored = BackupIO.write(target, Compression.NONE, 0, crypto?.spec) { out ->
                FileInputStream(src).use { BackupIO.progressCopy(it, out, progress.asByteProgress()) }
            }
            ApkFile(split, stored.copy(file = "apk/" + target.name))
        }
    }

    private suspend fun backupTree(
        path: String,
        base: String,
        excludes: List<String>,
        dir: File,
        crypto: CryptoSession?,
        progress: ProgressSink,
        warnings: MutableList<String>,
        what: String,
    ): DataPart? {
        val size = shell.sizeOf(path)
        if (size < 0) return null
        val excluded = excludes.sumOf { shell.sizeOf("$path/$it").coerceAtLeast(0) }
        progress.step("Backing up $what", (size - excluded).coerceAtLeast(0))
        val s = settings.current
        val target = File(dir, Codec.fileName("$base.tar", s.compression, crypto != null))
        val (stored, stats) = shell.tar(path, excludes) { input ->
            BackupIO.write(target, s.compression, s.compressionLevel, crypto?.spec) { out ->
                BackupIO.progressCopy(input, out, progress.asByteProgress())
            }
        }
        if (stats.skipped > 0) {
            warnings.add("$what: ${stats.skipped} item(s) could not be read and were skipped" +
                stats.skippedPaths.take(5).joinToString(prefix = " (", postfix = ")"))
        }
        if (stats.shrunk > 0) warnings.add("$what: ${stats.shrunk} file(s) changed while being backed up")
        return DataPart(stored, stats.files, stats.dirs, stats.skipped)
    }

    private fun saveIcon(packageName: String, file: File) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.toBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            file.delete()
        }
    }

    companion object {
        const val ICON_SIZE = 192
    }
}
