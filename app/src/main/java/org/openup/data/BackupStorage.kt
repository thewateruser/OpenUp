package org.openup.data

import kotlinx.serialization.json.Json
import org.openup.data.model.AppBackup
import org.openup.data.model.AppBackupMeta
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialBackupMeta
import org.openup.data.model.SpecialType
import org.openup.core.io.SyncingFileOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Layout of the backup folder:
 * ```
 * <root>/apps/<package>/<id>/meta.json, icon.png, apk/..., extdata.tar.zst[.enc], obb..., media...
 * <root>/<messages|calllogs|wallpapers|wifi>/<id>/meta.json, ...
 * ```
 * `<id>` is the UTC time of the backup (yyyyMMdd-HHmmss). Backups are written to `<id>.partial`
 * and renamed when complete, so an interrupted backup is never mistaken for a valid one.
 */
class BackupStorage(private val rootPath: () -> String) {
    constructor(settings: SettingsRepository) : this({ settings.current.backupDir })

    val root: File get() = File(rootPath())
    val appsDir: File get() = File(root, "apps")

    fun specialDir(type: SpecialType) = File(root, type.dirName)

    fun appDir(packageName: String): File {
        require(isSafeName(packageName)) { "invalid package name" }
        return File(appsDir, packageName)
    }

    /** Creates the root folder (with a .nomedia marker) and checks that it is writable. */
    fun ensureRoot(): File {
        val r = root
        if (!r.isDirectory && !r.mkdirs()) {
            throw IOException("Cannot create backup folder $r. Grant OpenUp storage access in the setup screen.")
        }
        val nomedia = File(r, ".nomedia")
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile()
            } catch (e: IOException) {
                throw IOException("Backup folder $r is not writable: ${e.message}", e)
            }
        }
        if (!r.canWrite()) throw IOException("Backup folder $r is not writable")
        return r
    }

    /** Creates a fresh `<id>.partial` directory under [parent] and returns (id, dir). */
    fun newPartial(parent: File): Pair<String, File> {
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
        // Leftovers from interrupted runs are useless; remove them.
        parent.listFiles()?.filter { it.isDirectory && it.name.endsWith(PARTIAL) }?.forEach { it.deleteRecursively() }
        var id = idFormat().format(Date())
        var n = 1
        while (File(parent, id).exists() || File(parent, id + PARTIAL).exists()) {
            id = idFormat().format(Date()) + "-" + n++
        }
        val dir = File(parent, id + PARTIAL)
        if (!dir.mkdirs()) throw IOException("Cannot create $dir")
        return id to dir
    }

    fun commitPartial(partial: File, id: String): File {
        val target = File(partial.parentFile, id)
        if (!partial.renameTo(target)) throw IOException("Could not finalize backup folder $target")
        return target
    }

    fun writeJson(file: File, text: String) {
        SyncingFileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun writeAppMeta(dir: File, meta: AppBackupMeta) = writeJson(File(dir, META), json.encodeToString(AppBackupMeta.serializer(), meta))

    fun writeSpecialMeta(dir: File, meta: SpecialBackupMeta) =
        writeJson(File(dir, META), json.encodeToString(SpecialBackupMeta.serializer(), meta))

    private fun versionDirs(parent: File): List<File> =
        parent.listFiles()?.filter { it.isDirectory && !it.name.endsWith(PARTIAL) && File(it, META).isFile }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun readAppBackup(dir: File): AppBackup? = try {
        val meta = json.decodeFromString(AppBackupMeta.serializer(), File(dir, META).readText())
        AppBackup(dir.name, dir, meta)
    } catch (_: Exception) {
        null
    }

    /** All versions of a package, newest first. */
    fun appBackups(packageName: String): List<AppBackup> =
        if (!isSafeName(packageName)) emptyList() else versionDirs(appDir(packageName)).mapNotNull { readAppBackup(it) }

    /** Newest backup of every package that has one. */
    fun latestAppBackups(): Map<String, AppBackup> {
        val result = HashMap<String, AppBackup>()
        appsDir.listFiles()?.filter { it.isDirectory && isSafeName(it.name) }?.forEach { pkgDir ->
            versionDirs(pkgDir).firstNotNullOfOrNull { readAppBackup(it) }?.let { result[pkgDir.name] = it }
        }
        return result
    }

    fun readSpecialBackup(type: SpecialType, dir: File): SpecialBackup? = try {
        SpecialBackup(type, dir.name, dir, json.decodeFromString(SpecialBackupMeta.serializer(), File(dir, META).readText()))
    } catch (_: Exception) {
        null
    }

    fun specialBackups(type: SpecialType): List<SpecialBackup> =
        versionDirs(specialDir(type)).mapNotNull { readSpecialBackup(type, it) }

    /** Deletes the oldest versions in [parent] so that at most [keep] remain. */
    fun prune(parent: File, keep: Int) {
        versionDirs(parent).drop(keep.coerceAtLeast(1)).forEach { it.deleteRecursively() }
    }

    fun delete(dir: File): Boolean {
        val ok = dir.deleteRecursively()
        // Remove the package folder once its last version is gone.
        dir.parentFile?.let { parent ->
            if (parent.parentFile == appsDir && parent.listFiles()?.isEmpty() == true) parent.delete()
        }
        return ok
    }

    companion object {
        const val META = "meta.json"
        const val ICON = "icon.png"
        const val PARTIAL = ".partial"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        private fun idFormat() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 255 && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')
    }
}
