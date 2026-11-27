package org.openup.data.special

import android.os.Build
import org.openup.core.io.Codec
import org.openup.core.io.CountingInputStream
import org.openup.core.io.CountingOutputStream
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.location.LocalNode
import org.openup.data.model.FolderBackup
import org.openup.data.model.SpecialBackupMeta
import org.openup.data.model.StoredFile
import org.openup.shell.FileOps
import org.openup.shizuku.ShellClient
import org.openup.shizuku.ShizukuManager
import org.openup.shizuku.ShizukuStatus
import org.openup.shizuku.TransferStats
import java.io.File
import java.io.IOException

/**
 * Backs up any folder on shared storage. With Shizuku the folder is read by the shell process,
 * which also reaches Android/data and Android/obb; without it OpenUp reads the folder itself
 * (all-files access), which works everywhere else.
 */
class FolderEngine(
    private val settings: SettingsRepository,
    private val storage: BackupStorage,
    private val shell: ShellClient,
    private val shizuku: ShizukuManager,
) {
    private fun useShell(path: String): Boolean {
        val ready = shizuku.status.value is ShizukuStatus.Ready
        if (!ready && needsShell(path)) throw IOException("Folders inside Android/data or Android/obb need Shizuku to be running")
        return ready
    }

    suspend fun backup(path: String, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        validate(path)?.let { throw IOException(it) }
        val backupRoot = localBackupRoot()
        val excludes = if (backupRoot != null && backupRoot.startsWith("$path/")) listOf(backupRoot.removePrefix("$path/")) else emptyList()
        storage.ensureRoot()
        val (id, partial) = storage.newPartial(storage.folderDir(path))
        try {
            val s = settings.current
            val target = Codec.fileName("folder.tar", s.compression, crypto != null)
            val (stored, stats) = if (useShell(path)) {
                val size = shell.sizeOf(path)
                if (size < 0) throw IOException("Folder not found: $path")
                val excluded = excludes.sumOf { shell.sizeOf("$path/$it").coerceAtLeast(0) }
                progress.step("Backing up $path", (size - excluded).coerceAtLeast(0))
                shell.tar(path, excludes) { input ->
                    BackupIO.write(partial, target, s.compression, s.compressionLevel, crypto?.spec) { out ->
                        BackupIO.progressCopy(input, out, progress.asByteProgress())
                    }
                }
            } else {
                val dir = File(path)
                if (!dir.isDirectory) throw IOException("Folder not found or not readable: $path")
                val excluded = excludes.sumOf { FileOps.sizeOf(File(dir, it)).coerceAtLeast(0) }
                progress.step("Backing up $path", (FileOps.sizeOf(dir) - excluded).coerceAtLeast(0))
                var tarStats: FileOps.TarStats? = null
                val stored: StoredFile = BackupIO.write(partial, target, s.compression, s.compressionLevel, crypto?.spec) { out ->
                    tarStats = FileOps.tar(dir, CountingOutputStream(out, progress.asByteProgress()), excludes.toSet())
                }
                val t = tarStats!!
                stored to TransferStats(t.files, t.dirs, t.bytes, t.skipped, t.shrunk, t.skippedPaths)
            }
            val meta = SpecialBackupMeta(
                type = FolderBackup.TYPE,
                backupTime = System.currentTimeMillis(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidSdk = Build.VERSION.SDK_INT,
                itemCount = stats.files,
                summary = "${stats.files} file${if (stats.files == 1) "" else "s"}",
                files = listOf(stored),
                extras = mapOf(FolderBackup.FOLDER_PATH_KEY to path),
            )
            storage.writeSpecialMeta(partial, meta, crypto?.spec)
            storage.commitPartial(partial, id)
            storage.prune(storage.folderDir(path), s.keepVersions)
            val warnings = buildList {
                if (stats.skipped > 0) {
                    add("${stats.skipped} item(s) could not be read and were skipped" +
                        stats.skippedPaths.take(5).joinToString(prefix = " (", postfix = ")"))
                }
                if (stats.shrunk > 0) add("${stats.shrunk} file(s) changed while being backed up")
                if (excludes.isNotEmpty()) add("The OpenUp backup folder inside it was not included")
            }
            return TaskOutcome("Backed up ${meta.summary}", warnings)
        } catch (t: Throwable) {
            try {
                partial.deleteRecursively()
            } catch (_: Exception) {
            }
            throw t
        }
    }

    suspend fun restore(backup: FolderBackup, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        val path = backup.path ?: throw IOException("Backup does not record its folder")
        // The path comes from the backup's metadata: never write anywhere a folder could not be
        // chosen from in the first place.
        if (normalize(path) != path) throw IOException("Backup records an invalid folder path")
        validate(path)?.let { throw IOException("Cannot restore to $path: $it") }
        val file = backup.meta.files.firstOrNull() ?: throw IOException("Backup is empty")
        progress.step("Restoring $path", file.originalSize)
        val stats = if (useShell(path)) {
            shell.untar(path, merge = true) { out ->
                BackupIO.open(backup.dir, file, crypto?.key).use { BackupIO.progressCopy(it, out, progress.asByteProgress()) }
            }
        } else {
            val t = BackupIO.open(backup.dir, file, crypto?.key).use { input ->
                FileOps.untar(File(path), CountingInputStream(input, progress.asByteProgress()), merge = true)
            }
            TransferStats(t.files, t.dirs, t.bytes, t.skipped, 0, t.skippedPaths)
        }
        val warnings = if (stats.skipped > 0) {
            listOf("${stats.skipped} item(s) could not be restored" + stats.skippedPaths.take(5).joinToString(prefix = " (", postfix = ")"))
        } else {
            emptyList()
        }
        return TaskOutcome("Restored ${stats.files} file${if (stats.files == 1) "" else "s"} to $path", warnings)
    }

    /** Returns an error message if [path] cannot be backed up as a folder, or null if it is fine. */
    fun validate(path: String): String? {
        val backupRoot = localBackupRoot()
        return when {
            !path.startsWith("/storage/") -> "Only folders on internal storage or SD cards (/storage/…) can be backed up"
            path.split('/').any { it == ".." || it == "." } -> "Invalid path"
            backupRoot != null && (path == backupRoot || path.startsWith("$backupRoot/")) -> "This is OpenUp's own backup folder"
            else -> null
        }
    }

    /** Path of the backup folder when it is on the phone (null for an external drive). */
    private fun localBackupRoot(): String? = (storage.root as? LocalNode)?.file?.path?.trimEnd('/')

    companion object {
        private val SHELL_ONLY = Regex("^/storage/emulated/\\d+/Android/(data|obb)(/|$)")

        fun needsShell(path: String) = SHELL_ONLY.containsMatchIn(path)

        /** Normalises a user supplied path (no trailing slash, no duplicate slashes). */
        fun normalize(path: String): String = File(path.trim()).path.trimEnd('/').ifEmpty { "/" }
    }
}
