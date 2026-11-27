package org.openup.core.io

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openup.core.crypto.PasswordKey
import org.openup.core.crypto.StreamCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@Serializable
enum class Compression(val extension: String) {
    @SerialName("none") NONE(""),
    @SerialName("zstd") ZSTD(".zst"),
}

/** Encryption settings used when writing. [kdfSalt] is shared by all files written with one password. */
class EncryptionSpec(val key: PasswordKey, val kdfSalt: ByteArray, val iterations: Int = PasswordKey.DEFAULT_ITERATIONS)

/**
 * Builds the write/read pipelines used for every backup file:
 * plaintext -> [zstd] -> [AES-GCM stream] -> file.
 */
object Codec {
    const val ENCRYPTED_EXTENSION = ".enc"
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    fun fileName(base: String, compression: Compression, encrypted: Boolean): String =
        base + compression.extension + if (encrypted) ENCRYPTED_EXTENSION else ""

    /** Wraps [sink]; closing the returned stream finishes every layer and closes [sink]. */
    fun encoder(sink: OutputStream, compression: Compression, level: Int, encryption: EncryptionSpec?): OutputStream {
        var out: OutputStream = BufferedOutputStream(sink, 256 * 1024)
        if (encryption != null) {
            out = StreamCrypto.encrypt(out, encryption.key, encryption.kdfSalt, encryption.iterations)
        }
        if (compression == Compression.ZSTD) {
            val z = ZstdOutputStreamNoFinalizer(out, level.coerceIn(1, 19))
            z.setChecksum(true)
            out = z
        }
        return BufferedOutputStream(out, 256 * 1024)
    }

    /**
     * Wraps [source] for reading. Throws [EncryptedBackupException] if the data is encrypted and
     * no key is available, and verifies the format against the metadata.
     */
    fun decoder(source: InputStream, compression: Compression, encrypted: Boolean, key: PasswordKey?): InputStream {
        var input: InputStream = BufferedInputStream(source, 256 * 1024)
        // The metadata is authoritative; the magic bytes are only used to report a clear error
        // when the file does not match it.
        if (encrypted) {
            input.mark(16)
            val head = ByteArray(8)
            val n = readUpTo(input, head)
            input.reset()
            if (n < 7 || !StreamCrypto.looksEncrypted(head)) throw IOException("Backup file is not encrypted as expected (corrupted?)")
            if (key == null) throw EncryptedBackupException()
            input = StreamCrypto.decrypt(input, key)
        }
        if (compression == Compression.ZSTD) {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(8)
            val zh = ByteArray(4)
            val zn = readUpTo(buffered, zh)
            buffered.reset()
            if (zn < 4 || !zh.contentEquals(ZSTD_MAGIC)) throw IOException("Backup file is not zstd compressed (corrupted?)")
            input = ZstdInputStreamNoFinalizer(buffered)
        }
        return BufferedInputStream(input, 256 * 1024)
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}

class EncryptedBackupException : IOException("This backup is encrypted. Set the backup password in Settings to restore it.")
