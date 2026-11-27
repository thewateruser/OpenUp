package org.openup.data.location

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * A [StorageNode] inside a folder that the user picked with the system folder picker (Storage
 * Access Framework). This is how apps reach USB drives, which Android does not mount as a path.
 *
 * Nodes are resolved lazily: a child that was created with [child] looks itself up by name the
 * first time it is used.
 */
class SafNode private constructor(
    private val context: Context,
    private val treeUri: Uri,
    @Volatile private var documentId: String?,
    override val parent: SafNode?,
    private var knownName: String?,
    @Volatile private var knownMime: String?,
    @Volatile private var knownSize: Long,
) : StorageNode {

    private val cr get() = context.contentResolver

    override val name: String
        get() = knownName ?: (query(documentUri(requireId()))?.name ?: "").also { knownName = it }

    override val id: String get() = documentUri(requireId()).toString()

    private fun documentUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun requireId(): String = resolveId() ?: throw IOException("${knownName ?: "Entry"} does not exist on the backup drive")

    /** Document id, or null if the entry does not exist (yet). */
    private fun resolveId(): String? {
        documentId?.let { return it }
        val p = parent ?: return null
        val n = knownName ?: return null
        val match = p.listRows().firstOrNull { it.name == n } ?: return null
        documentId = match.docId
        knownMime = match.mime
        knownSize = match.size
        return match.docId
    }

    private class Row(val docId: String, val name: String, val mime: String, val size: Long)

    private fun rowOf(c: Cursor) = Row(
        docId = c.getString(0),
        name = c.getString(1) ?: "",
        mime = c.getString(2) ?: "",
        size = if (c.isNull(3)) 0L else c.getLong(3),
    )

    private fun query(uri: Uri): Row? = guard {
        cr.query(uri, PROJECTION, null, null, null)?.use { c -> if (c.moveToFirst()) rowOf(c) else null }
    }

    private fun listRows(): List<Row> {
        val docId = resolveId() ?: return emptyList()
        return guard {
            cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId), PROJECTION, null, null, null)?.use { c ->
                val rows = ArrayList<Row>(c.count)
                while (c.moveToNext()) rows.add(rowOf(c))
                rows
            } ?: emptyList()
        }
    }

    /** Fresh metadata of this entry, or null if it does not exist. */
    private fun stat(): Row? {
        val docId = resolveId() ?: return null
        val row = query(documentUri(docId))
        if (row == null) {
            // Deleted or renamed behind our back: look it up again next time.
            if (parent != null) documentId = null
            knownMime = null
            return null
        }
        knownMime = row.mime
        knownSize = row.size
        return row
    }

    override fun exists() = stat() != null

    // Nodes from a directory listing already know their type; avoid one query per entry.
    override fun isDirectory() = (knownMime ?: stat()?.mime) == Document.MIME_TYPE_DIR

    override fun length(): Long = stat()?.takeIf { it.mime != Document.MIME_TYPE_DIR }?.size ?: 0L

    override fun child(name: String): StorageNode = SafNode(context, treeUri, null, this, name, null, 0L)

    override fun list(): List<StorageNode> = listRows().map { SafNode(context, treeUri, it.docId, this, it.name, it.mime, it.size) }

    override fun mkdirs() {
        val row = stat()
        if (row != null) {
            if (row.mime != Document.MIME_TYPE_DIR) throw IOException("$name exists on the backup drive but is not a folder")
            return
        }
        val p = parent ?: throw IOException("The backup drive or folder is not available")
        p.mkdirs()
        create(p, Document.MIME_TYPE_DIR)
    }

    /** Creates this entry in [p] and checks that the provider kept the requested name. */
    private fun create(p: SafNode, mime: String) {
        val wanted = knownName ?: throw IOException("Missing name")
        val uri = guard { DocumentsContract.createDocument(cr, p.documentUri(p.requireId()), mime, wanted) }
            ?: throw IOException("Could not create $wanted on the backup drive")
        val created = query(uri)
        if (created == null || created.name != wanted) {
            guard { DocumentsContract.deleteDocument(cr, uri) }
            throw IOException("The backup drive does not accept the name \"$wanted\"")
        }
        documentId = DocumentsContract.getDocumentId(uri)
        knownMime = created.mime
        knownSize = created.size
    }

    override fun openInput(): InputStream {
        val uri = documentUri(requireId())
        val pfd = guard { cr.openFileDescriptor(uri, "r") } ?: throw IOException("Cannot open $name on the backup drive")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    override fun openOutput(): OutputStream {
        val p = parent ?: throw IOException("Cannot create a file here")
        // Replace an existing file instead of relying on truncate support in the provider.
        if (stat() != null && !deleteRecursively()) throw IOException("Cannot replace $name on the backup drive")
        p.mkdirs()
        create(p, "application/octet-stream")
        val pfd = guard { cr.openFileDescriptor(documentUri(requireId()), "w") }
            ?: throw IOException("Cannot write $name on the backup drive")
        return PfdOutputStream(pfd)
    }

    override fun deleteRecursively(): Boolean {
        val docId = resolveId() ?: return true
        val ok = try {
            guard { DocumentsContract.deleteDocument(cr, documentUri(docId)) }
        } catch (_: IOException) {
            false
        }
        if (ok) {
            documentId = null
            knownMime = null
            return true
        }
        return query(documentUri(docId)) == null
    }

    override fun renameTo(newName: String): StorageNode {
        val uri = guard { DocumentsContract.renameDocument(cr, documentUri(requireId()), newName) }
            ?: throw IOException("The backup drive could not rename $name")
        val row = query(uri)
        if (row == null || row.name != newName) {
            try {
                guard { DocumentsContract.deleteDocument(cr, uri) }
            } catch (_: IOException) {
            }
            throw IOException("The backup drive renamed $name unexpectedly")
        }
        documentId = null // this node now refers to the old name, which no longer exists
        knownMime = null
        return SafNode(context, treeUri, DocumentsContract.getDocumentId(uri), parent, newName, row.mime, row.size)
    }

    override fun toString() = "SafNode(${knownName ?: documentId})"

    private class PfdOutputStream(private val pfd: ParcelFileDescriptor) : FileOutputStream(pfd.fileDescriptor) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
                try {
                    fd.sync()
                } catch (_: SyncFailedException) {
                    // Providers that hand out pipes cannot sync; the data is still written.
                }
            } finally {
                try {
                    super.close()
                } finally {
                    pfd.close()
                }
            }
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )

        /** The folder the user picked (a tree URI from ACTION_OPEN_DOCUMENT_TREE). */
        fun root(context: Context, treeUri: Uri): SafNode =
            SafNode(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), null, null, Document.MIME_TYPE_DIR, 0L)

        /** A node from a document URI produced by [id]. */
        fun fromDocumentUri(context: Context, documentUri: Uri): SafNode {
            val tree = DocumentsContract.buildTreeDocumentUri(documentUri.authority, DocumentsContract.getTreeDocumentId(documentUri))
            return SafNode(context, tree, DocumentsContract.getDocumentId(documentUri), null, null, null, 0L)
        }

        /** Runs a provider call, turning "drive removed" style failures into IOExceptions. */
        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw e
        } catch (e: SecurityException) {
            throw IOException("OpenUp lost access to the backup drive. Choose the backup folder again in Settings.", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("The backup drive is not available. Is it plugged in?", e)
        } catch (e: IllegalStateException) {
            throw IOException("The backup drive is not available. Is it plugged in?", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("The backup location does not support this operation", e)
        }
    }
}
