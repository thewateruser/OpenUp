package org.openup.data.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.openup.data.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the "backup location" setting into storage nodes. The setting holds either an absolute
 * path on the phone or a content:// tree URI of a folder picked with the system folder picker
 * (USB drive, SD card, …). On a picked folder OpenUp keeps its backups in an "OpenUp" sub-folder,
 * unless the picked folder already is named OpenUp.
 */
class BackupLocations(private val context: Context, private val settings: SettingsRepository) {
    private val usesSubfolder = ConcurrentHashMap<String, Boolean>()

    private val location: String get() = settings.current.backupDir

    val isExternal: Boolean get() = isTreeUri(location)

    fun root(): StorageNode {
        val loc = location
        if (!isTreeUri(loc)) return LocalNode(File(loc))
        val picked = SafNode.root(context, Uri.parse(loc))
        val sub = usesSubfolder[loc] ?: try {
            (!picked.name.equals(FOLDER_NAME, ignoreCase = true)).also { usesSubfolder[loc] = it }
        } catch (_: IOException) {
            true // drive not connected; every operation will report that
        }
        return if (sub) picked.child(FOLDER_NAME) else picked
    }

    /** Node for an id produced by [StorageNode.id]. */
    fun node(id: String): StorageNode =
        if (isTreeUri(id)) SafNode.fromDocumentUri(context, Uri.parse(id)) else LocalNode(File(id))

    /** Why the external backup location cannot be used right now, or null if it is fine (or local). */
    fun externalProblem(): String? {
        val loc = location
        if (!isTreeUri(loc)) return null
        val uri = Uri.parse(loc)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!granted) return "OpenUp no longer has access to the backup drive. Choose the backup folder again in Settings."
        return try {
            if (SafNode.root(context, uri).exists()) null else NOT_CONNECTED
        } catch (_: IOException) {
            NOT_CONNECTED
        }
    }

    /** Human readable description of the current location. */
    fun describe(): String {
        val loc = location
        if (!isTreeUri(loc)) return loc
        val picked = try {
            SafNode.root(context, Uri.parse(loc)).name
        } catch (_: IOException) {
            null
        }
        return if (picked == null) {
            "External drive (not connected)"
        } else if (picked.equals(FOLDER_NAME, ignoreCase = true)) {
            "External drive · $picked"
        } else {
            "External drive · $picked/$FOLDER_NAME"
        }
    }

    /** Switches to a folder picked with the system folder picker and keeps access to it. */
    fun useExternal(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val previous = location
        settings.update { it.copy(backupDir = treeUri.toString()) }
        releaseIfUnused(previous)
    }

    fun useLocal(path: String) {
        val previous = location
        settings.update { it.copy(backupDir = path) }
        releaseIfUnused(previous)
    }

    private fun releaseIfUnused(previous: String) {
        if (!isTreeUri(previous) || previous == location) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val FOLDER_NAME = "OpenUp"
        const val NOT_CONNECTED = "The backup drive is not connected. Plug in the USB drive and try again."

        fun isTreeUri(value: String) = value.startsWith("content://")
    }
}
