package org.openup.data

import kotlinx.serialization.json.Json
import org.openup.core.crypto.PasswordKey
import org.openup.core.io.EncryptionSpec
import org.openup.data.location.LocalNode
import org.openup.data.location.StorageNode
import org.openup.data.location.readText
import org.openup.data.location.writeText
import org.openup.data.model.AppBackup
import org.openup.data.model.AppBackupMeta
import org.openup.data.model.FolderBackup
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialBackupMeta
import org.openup.data.model.SpecialType
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
 * <root>/folders/<name>_<hash>/<id>/meta.json, folder.tar...
 * ```
 * `<id>` is the UTC time of the backup (yyyyMMdd-HHmmss). Backups are written to `<id>.partial`
 * and renamed when complete, so an interrupted backup is never mistaken for a valid one.
 * The root is a folder on the phone or on an external drive (see [org.openup.data.location]).
 */
class BackupStorage(
    private val rootProvider: () -> StorageNode,
    private val nodeResolver: (String) -> StorageNode = { LocalNode(File(it)) },
) {
    val root: StorageNode get() = rootProvider()
    val appsDir: StorageNode get() = root.child("apps")
    val foldersDir: StorageNode get() = root.child("folders")

    fun specialDir(type: SpecialType) = root.child(type.dirName)

    /** Backups of the folder at [path] live in their own sub-folder, so they are pruned separately. */
    fun folderDir(path: String) = foldersDir.child(folderKey(path))

    fun appDir(packageName: String): StorageNode {
        require(isSafeName(packageName)) { "invalid package name" }
        return appsDir.child(packageName)
    }

    /** Node for an id from [StorageNode.id] (e.g. a backup folder stored in a job). */
    fun node(id: String): StorageNode = nodeResolver(id)

    /** Creates the root folder (with a .nomedia marker) and checks that it is usable. */
    fun ensureRoot(): StorageNode {
        val r = root
        try {
            r.mkdirs()
        } catch (e: IOException) {
            if (r is LocalNode) {
                throw IOException("Cannot create backup folder ${r.file}. Grant OpenUp storage access in the setup screen.", e)
            }
            throw e
        }
        if (r is LocalNode && !r.file.canWrite()) throw IOException("Backup folder ${r.file} is not writable")
        val nomedia = r.child(".nomedia")
        try {
            if (!nomedia.exists()) nomedia.openOutput().close()
        } catch (_: IOException) {
            // Only keeps gallery apps from scanning backups; not essential.
        }
        return r
    }

    /** Creates a fresh `<id>.partial` directory under [parent] and returns (id, dir). */
    fun newPartial(parent: StorageNode): Pair<String, StorageNode> {
        parent.mkdirs()
        val existing = parent.list()
        // Leftovers from interrupted runs are useless; remove them.
        existing.filter { it.name.endsWith(PARTIAL) && it.isDirectory() }.forEach { it.deleteRecursively() }
        val names = existing.map { it.name }.toSet()
        val base = idFormat().format(Date())
        var id = base
        var n = 1
        while (id in names || id + PARTIAL in names) id = base + "-" + n++
        val dir = parent.child(id + PARTIAL)
        dir.mkdirs()
        return id to dir
    }

    fun commitPartial(partial: StorageNode, id: String): StorageNode = partial.renameTo(id)

    fun writeAppMeta(dir: StorageNode, meta: AppBackupMeta, encryption: EncryptionSpec?) =
        writeMeta(dir, json.encodeToString(AppBackupMeta.serializer(), meta), encryption)

    fun writeSpecialMeta(dir: StorageNode, meta: SpecialBackupMeta, encryption: EncryptionSpec?) =
        writeMeta(dir, json.encodeToString(SpecialBackupMeta.serializer(), meta), encryption)

    /** Writes meta.json and, for encrypted backups, its signature (see [MetaSignature]). */
    private fun writeMeta(dir: StorageNode, text: String, encryption: EncryptionSpec?) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        dir.child(META).openOutput().use { it.write(bytes) }
        if (encryption != null) {
            dir.child(MetaSignature.FILE).writeText(json.encodeToString(MetaSignature.Sig.serializer(), MetaSignature.sign(bytes, encryption)))
        }
    }

    /**
     * Reads meta.json again for a restore and checks it (see [MetaSignature.verify]). The returned
     * bytes are what the restore must use, so a file swapped after listing is never trusted.
     */
    private fun verifiedMeta(dir: StorageNode, key: PasswordKey?, allowUnsigned: Boolean, claimsEncrypted: (ByteArray) -> Boolean): ByteArray {
        val bytes = try {
            dir.child(META).openInput().use { it.readBytes() }
        } catch (e: IOException) {
            throw IOException("The selected backup no longer exists or is unreadable", e)
        }
        val sigNode = dir.child(MetaSignature.FILE)
        val sig = if (sigNode.exists()) {
            try {
                json.decodeFromString(MetaSignature.Sig.serializer(), sigNode.readText())
            } catch (e: Exception) {
                throw IOException("Corrupted backup signature", e)
            }
        } else {
            null
        }
        val encrypted = try {
            claimsEncrypted(bytes)
        } catch (e: Exception) {
            throw IOException("The backup's metadata is unreadable", e)
        }
        MetaSignature.verify(bytes, sig, encrypted, key, allowUnsigned)
        return bytes
    }

    /** True if the backup in [dir] carries a signature (i.e. was made with a password). */
    fun isSigned(dir: StorageNode): Boolean = try {
        dir.child(MetaSignature.FILE).exists()
    } catch (_: IOException) {
        false
    }

    fun loadAppBackupForRestore(dir: StorageNode, key: PasswordKey?, expectedPackage: String, allowUnsigned: Boolean = false): AppBackup {
        val bytes = verifiedMeta(dir, key, allowUnsigned) { json.decodeFromString(AppBackupMeta.serializer(), it.toString(Charsets.UTF_8)).encrypted }
        val meta = json.decodeFromString(AppBackupMeta.serializer(), bytes.toString(Charsets.UTF_8))
        if (meta.packageName != expectedPackage) throw IOException("This backup belongs to ${meta.packageName}, not $expectedPackage")
        return AppBackup(dir.name, dir, meta)
    }

    fun loadSpecialBackupForRestore(type: SpecialType, dir: StorageNode, key: PasswordKey?, allowUnsigned: Boolean = false): SpecialBackup {
        val bytes = verifiedMeta(dir, key, allowUnsigned) { json.decodeFromString(SpecialBackupMeta.serializer(), it.toString(Charsets.UTF_8)).encrypted }
        val meta = json.decodeFromString(SpecialBackupMeta.serializer(), bytes.toString(Charsets.UTF_8))
        if (meta.type != type.dirName) throw IOException("This is not a ${type.title.lowercase()} backup")
        return SpecialBackup(type, dir.name, dir, meta)
    }

    fun loadFolderBackupForRestore(dir: StorageNode, key: PasswordKey?, allowUnsigned: Boolean = false): FolderBackup {
        val bytes = verifiedMeta(dir, key, allowUnsigned) { json.decodeFromString(SpecialBackupMeta.serializer(), it.toString(Charsets.UTF_8)).encrypted }
        val meta = json.decodeFromString(SpecialBackupMeta.serializer(), bytes.toString(Charsets.UTF_8))
        if (meta.type != FolderBackup.TYPE || meta.extras[FolderBackup.FOLDER_PATH_KEY] == null) throw IOException("This is not a folder backup")
        return FolderBackup(dir.name, dir, meta)
    }

    /** Candidate version folders, newest first. Folders without valid metadata are skipped by the readers. */
    private fun versionDirs(parent: StorageNode): List<StorageNode> =
        parent.list().filter { !it.name.endsWith(PARTIAL) && isSafeName(it.name) && it.isDirectory() }
            .sortedByDescending { it.name }

    fun readAppBackup(dir: StorageNode): AppBackup? = try {
        val meta = json.decodeFromString(AppBackupMeta.serializer(), dir.child(META).readText())
        // A backup must describe the app whose folder it is in.
        val folderPackage = dir.parent?.name
        if (!isSafeName(meta.packageName) || (folderPackage != null && folderPackage != meta.packageName)) null
        else AppBackup(dir.name, dir, meta)
    } catch (_: Exception) {
        null
    }

    /** All versions of a package, newest first. */
    fun appBackups(packageName: String): List<AppBackup> =
        if (!isSafeName(packageName)) emptyList() else versionDirs(appDir(packageName)).mapNotNull { readAppBackup(it) }

    /** Newest backup of every package that has one. */
    fun latestAppBackups(): Map<String, AppBackup> {
        val result = HashMap<String, AppBackup>()
        appsDir.list().filter { isSafeName(it.name) && it.isDirectory() }.forEach { pkgDir ->
            versionDirs(pkgDir).firstNotNullOfOrNull { readAppBackup(it) }?.let { result[pkgDir.name] = it }
        }
        return result
    }

    fun readSpecialBackup(type: SpecialType, dir: StorageNode): SpecialBackup? = try {
        SpecialBackup(type, dir.name, dir, json.decodeFromString(SpecialBackupMeta.serializer(), dir.child(META).readText()))
    } catch (_: Exception) {
        null
    }

    fun specialBackups(type: SpecialType): List<SpecialBackup> =
        versionDirs(specialDir(type)).mapNotNull { readSpecialBackup(type, it) }

    fun readFolderBackup(dir: StorageNode): FolderBackup? = try {
        val meta = json.decodeFromString(SpecialBackupMeta.serializer(), dir.child(META).readText())
        if (meta.type == FolderBackup.TYPE && meta.extras[FolderBackup.FOLDER_PATH_KEY] != null) FolderBackup(dir.name, dir, meta) else null
    } catch (_: Exception) {
        null
    }

    /** Every folder backup, newest first. */
    fun folderBackups(): List<FolderBackup> =
        foldersDir.list().filter { it.isDirectory() }
            .flatMap { versionDirs(it).mapNotNull { v -> readFolderBackup(v) } }
            .sortedByDescending { it.meta.backupTime }

    /** Deletes the oldest valid versions in [parent] so that at most [keep] remain. */
    fun prune(parent: StorageNode, keep: Int) {
        versionDirs(parent).filter { v -> isValidVersion(v) }.drop(keep.coerceAtLeast(1)).forEach { it.deleteRecursively() }
    }

    private fun isValidVersion(dir: StorageNode): Boolean = try {
        json.parseToJsonElement(dir.child(META).readText())
        true
    } catch (_: Exception) {
        false
    }

    fun delete(dir: StorageNode): Boolean {
        val ok = dir.deleteRecursively()
        // Remove the package / folder container once its last version is gone.
        val container = dir.parent
        val group = container?.parent
        if (container != null && group != null && (group.name == "apps" || group.name == "folders")) {
            try {
                if (container.list().isEmpty()) container.deleteRecursively()
            } catch (_: IOException) {
            }
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

        /** Stable, file-system safe folder name for a backed up path. */
        fun folderKey(path: String): String {
            val slug = path.trim('/').replace(Regex("[^A-Za-z0-9._-]+"), "_").takeLast(60).trim('_', '.')
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
                .take(5).joinToString("") { "%02x".format(it) }
            return (if (slug.isEmpty()) "root" else slug) + "_" + hash
        }

        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 255 && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')
    }
}
