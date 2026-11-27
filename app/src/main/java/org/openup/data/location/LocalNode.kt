package org.openup.data.location

import org.openup.core.io.SyncingFileOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A [StorageNode] backed by a regular file path. */
class LocalNode(val file: File) : StorageNode {
    override val id: String get() = file.path
    override val name: String get() = file.name
    override val parent: StorageNode? get() = file.parentFile?.let { LocalNode(it) }

    override fun exists() = file.exists()
    override fun isDirectory() = file.isDirectory
    override fun length() = if (file.isFile) file.length() else 0L
    override fun child(name: String): StorageNode = LocalNode(File(file, name))
    override fun list(): List<StorageNode> = file.listFiles()?.map { LocalNode(it) } ?: emptyList()

    override fun mkdirs() {
        if (file.isDirectory) return
        if (!file.mkdirs() && !file.isDirectory) throw IOException("Cannot create folder $file")
    }

    override fun openInput(): InputStream = FileInputStream(file)

    override fun openOutput(): OutputStream {
        file.parentFile?.let { LocalNode(it).mkdirs() }
        return SyncingFileOutputStream(file)
    }

    override fun deleteRecursively(): Boolean = file.deleteRecursively() || !file.exists()

    override fun renameTo(newName: String): StorageNode {
        val target = File(file.parentFile, newName)
        if (!file.renameTo(target)) throw IOException("Could not rename $file to $newName")
        return LocalNode(target)
    }

    override fun toString() = file.path
}
