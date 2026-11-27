package org.openup.data

import org.openup.core.crypto.PasswordKey
import org.openup.core.io.ByteProgress
import org.openup.core.io.Codec
import org.openup.core.io.Compression
import org.openup.core.io.CountingOutputStream
import org.openup.core.io.EncryptionSpec
import org.openup.data.location.StorageNode
import org.openup.data.location.resolve
import org.openup.data.model.StoredFile
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Reads and writes individual backup files through the compression/encryption pipeline. */
object BackupIO {
    /** Files are split into pieces of this size so they fit on FAT32 formatted USB drives. */
    const val DEFAULT_PART_SIZE = 2L * 1024 * 1024 * 1024

    fun partName(file: String, index: Int) = if (index == 0) file else "$file.part${index + 1}"

    /**
     * Creates [name] in [dir] and lets [block] write the decoded data into it. The data is hashed
     * (SHA-256) on the way; files larger than [partSize] are split into several pieces.
     */
    fun write(
        dir: StorageNode,
        name: String,
        compression: Compression,
        level: Int,
        encryption: EncryptionSpec?,
        partSize: Long = DEFAULT_PART_SIZE,
        block: (OutputStream) -> Unit,
    ): StoredFile {
        val digest = MessageDigest.getInstance("SHA-256")
        val created = ArrayList<StorageNode>()
        var original = 0L
        val split = SplitOutputStream(partSize) { index ->
            val node = dir.child(partName(name, index))
            created.add(node)
            node.openOutput()
        }
        try {
            split.openFirst()
            val encoder = Codec.encoder(split, compression, level, encryption)
            encoder.use { enc ->
                val counting = CountingOutputStream(DigestOutputStream(enc, digest))
                block(counting)
                counting.flush()
                original = counting.count
            }
            val onDisk = created.sumOf { it.length() }
            if (onDisk != split.totalBytes) {
                throw IOException("Backup file $name was not written completely ($onDisk of ${split.totalBytes} bytes)")
            }
        } catch (t: Throwable) {
            try {
                split.close()
            } catch (_: Exception) {
            }
            created.forEach {
                try {
                    it.deleteRecursively()
                } catch (_: Exception) {
                }
            }
            throw t
        }
        return StoredFile(
            file = name,
            originalSize = original,
            storedSize = split.totalBytes,
            compression = compression,
            encrypted = encryption != null,
            sha256 = hex(digest.digest()),
            parts = created.size,
        )
    }

    /**
     * Opens a stored file for reading its decoded contents. When [verify] is true the SHA-256 is
     * checked at the end of the stream and a mismatch raises an [IOException].
     */
    fun open(dir: StorageNode, stored: StoredFile, key: PasswordKey?, verify: Boolean = true): InputStream {
        val pieces = (0 until stored.parts.coerceAtLeast(1)).map { dir.resolve(partName(stored.file, it)) }
        var size = 0L
        for (p in pieces) {
            if (!p.exists()) throw IOException("Backup file ${p.name} is missing")
            size += p.length()
        }
        if (size != stored.storedSize) {
            throw IOException("Backup file ${stored.file} has the wrong size (incomplete copy?)")
        }
        val raw: InputStream = if (pieces.size == 1) pieces[0].openInput() else ConcatInputStream(pieces)
        val decoded = Codec.decoder(raw, stored.compression, stored.encrypted, key)
        val expected = stored.sha256
        return if (verify && expected != null) VerifyingInputStream(decoded, expected, stored.originalSize, stored.file) else decoded
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun progressCopy(input: InputStream, output: OutputStream, progress: ByteProgress): Long =
        org.openup.core.io.Streams.copy(input, output, progress)

    /** Reads the pieces of a split file one after another, opening each only when it is needed. */
    private class ConcatInputStream(private val pieces: List<StorageNode>) : InputStream() {
        private var index = 0
        private var current: InputStream? = pieces[0].openInput()

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val c = current ?: return -1
                val n = c.read(b, off, len)
                if (n >= 0) return n
                c.close()
                index++
                current = if (index < pieces.size) pieces[index].openInput() else null
            }
        }

        override fun close() {
            current?.close()
            current = null
        }
    }

    /** Writes into a sequence of outputs, starting a new one every [partSize] bytes. */
    private class SplitOutputStream(
        private val partSize: Long,
        private val openPart: (Int) -> OutputStream,
    ) : OutputStream() {
        private var current: OutputStream? = null
        private var index = -1
        private var inPart = 0L
        private var closed = false
        var totalBytes = 0L
            private set

        fun openFirst() {
            check(index == -1)
            next()
        }

        private fun next() {
            current?.close()
            current = null
            index++
            current = openPart(index)
            inPart = 0
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var o = off
            var l = len
            while (l > 0) {
                if (inPart >= partSize) next()
                val n = minOf(l.toLong(), partSize - inPart).toInt()
                current!!.write(b, o, n)
                inPart += n
                totalBytes += n
                o += n
                l -= n
            }
        }

        override fun flush() {
            current?.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            current?.close()
            current = null
        }
    }

    private class DigestOutputStream(out: OutputStream, private val digest: MessageDigest) : FilterOutputStream(out) {
        override fun write(b: Int) {
            out.write(b)
            digest.update(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            digest.update(b, off, len)
        }
    }

    /** Hashes everything read and verifies size and digest once the end of the data is reached. */
    private class VerifyingInputStream(
        input: InputStream,
        private val expected: String,
        private val expectedSize: Long,
        private val name: String,
    ) : FilterInputStream(input) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var verified = false

        override fun read(): Int {
            val b = super.read()
            if (b < 0) verify() else {
                digest.update(b.toByte())
                count++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n < 0) verify() else if (n > 0) {
                digest.update(b, off, n)
                count += n
            }
            return n
        }

        override fun skip(n: Long): Long = throw IOException("skip not supported")

        override fun markSupported() = false

        private fun verify() {
            if (verified) return
            verified = true
            if (count != expectedSize || hex(digest.digest()) != expected) {
                throw IOException("Backup file $name is corrupted (checksum mismatch)")
            }
        }
    }
}
