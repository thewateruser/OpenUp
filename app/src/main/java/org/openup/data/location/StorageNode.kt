package org.openup.data.location

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A file or directory in the backup location. The location is either a folder on the phone
 * ([LocalNode]) or a folder on a USB drive / SD card / other provider chosen through the system
 * folder picker ([SafNode]). Nodes may refer to entries that do not exist yet.
 */
interface StorageNode {
    /** Stable identifier that [BackupLocations.node] turns back into a node (a path or a document URI). */
    val id: String
    val name: String

    /** The containing directory, or null when it is not known. */
    val parent: StorageNode?

    fun exists(): Boolean
    fun isDirectory(): Boolean

    /** Size in bytes of a file, 0 for directories or missing entries. */
    fun length(): Long

    fun child(name: String): StorageNode

    /** Entries of a directory; empty if this is not an existing directory. */
    fun list(): List<StorageNode>

    /** Creates this directory and missing parents. Throws if that is not possible. */
    fun mkdirs()

    fun openInput(): InputStream

    /** Creates (or replaces) this file. Data is flushed to the device when the stream is closed. */
    fun openOutput(): OutputStream

    /** Deletes this entry and everything below it. Returns true if it no longer exists. */
    fun deleteRecursively(): Boolean

    /** Renames this entry within its directory and returns the renamed node. */
    fun renameTo(newName: String): StorageNode
}

/** Resolves a relative path like "apk/base.apk". */
fun StorageNode.resolve(relativePath: String): StorageNode =
    relativePath.split('/').filter { it.isNotEmpty() }.fold(this) { node, part ->
        if (part == "." || part == "..") throw IOException("Invalid path $relativePath")
        node.child(part)
    }

fun StorageNode.readText(): String = openInput().use { it.readBytes().toString(Charsets.UTF_8) }

fun StorageNode.writeText(text: String) {
    openOutput().use { it.write(text.toByteArray(Charsets.UTF_8)) }
}
