package org.openup.data

import org.openup.core.crypto.PasswordKey
import org.openup.core.io.ByteProgress
import org.openup.core.io.Codec
import org.openup.core.io.Compression
import org.openup.core.io.CountingOutputStream
import org.openup.core.io.EncryptionSpec
import org.openup.core.io.SyncingFileOutputStream
import org.openup.data.model.StoredFile
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Reads and writes individual backup files through the compression/encryption pipeline. */
object BackupIO {
    /**
     * Creates [file] and lets [block] write the decoded data into it.
     * The data is hashed (SHA-256) on the way and the result describes the stored file.
     */
    fun write(
        file: File,
        compression: Compression,
        level: Int,
        encryption: EncryptionSpec?,
        block: (OutputStream) -> Unit,
    ): StoredFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var original = 0L
        try {
            val encoder = Codec.encoder(SyncingFileOutputStream(file), compression, level, encryption)
            encoder.use { enc ->
                val counting = CountingOutputStream(DigestOutputStream(enc, digest))
                block(counting)
                counting.flush()
                original = counting.count
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return StoredFile(
            file = file.name,
            originalSize = original,
            storedSize = file.length(),
            compression = compression,
            encrypted = encryption != null,
            sha256 = hex(digest.digest()),
        )
    }

    /**
     * Opens a stored file for reading its decoded contents. When [verify] is true the SHA-256 is
     * checked at the end of the stream and a mismatch raises an [IOException].
     */
    fun open(dir: File, stored: StoredFile, key: PasswordKey?, verify: Boolean = true): InputStream {
        val file = File(dir, stored.file)
        if (!file.isFile) throw IOException("Backup file ${stored.file} is missing")
        if (file.length() != stored.storedSize) {
            throw IOException("Backup file ${stored.file} has the wrong size (incomplete copy?)")
        }
        val decoded = Codec.decoder(FileInputStream(file), stored.compression, stored.encrypted, key)
        val expected = stored.sha256
        return if (verify && expected != null) VerifyingInputStream(decoded, expected, stored.originalSize, stored.file) else decoded
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun progressCopy(input: InputStream, output: OutputStream, progress: ByteProgress): Long =
        org.openup.core.io.Streams.copy(input, output, progress)

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
