package org.openup.core.crypto

import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated streaming encryption for backup files.
 *
 * Format (all integers big endian):
 * ```
 * magic "OPENUPE" (7) | version 0x01 (1) | kdf 0x01 = PBKDF2-HMAC-SHA256 (1) | iterations (4)
 * | kdf salt (16) | file salt (16) | nonce prefix (7) | chunk size (4)
 * followed by chunks: AES-256-GCM(chunk plaintext) || 16 byte tag
 * ```
 * Every chunk except the last carries exactly `chunk size` plaintext bytes. The 12 byte nonce of
 * chunk `i` is `nonce prefix || i (4 bytes) || last-flag (1 byte)` (the STREAM construction), which
 * protects against reordering, truncation and extension. The per-file key is
 * HKDF-SHA256(PBKDF2(password), file salt, info = "OpenUp stream v1" || header), binding the whole
 * header to the key.
 */
object StreamCrypto {
    private val MAGIC = "OPENUPE".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val NONCE_PREFIX_LEN = 7
    private const val TAG_LEN = 16
    const val HEADER_LEN = 7 + 1 + 1 + 4 + 16 + 16 + NONCE_PREFIX_LEN + 4
    const val DEFAULT_CHUNK_SIZE = 1 shl 20
    private const val MIN_CHUNK = 1 shl 10
    private const val MAX_CHUNK = 1 shl 24
    private val INFO = "OpenUp stream v1".toByteArray(StandardCharsets.US_ASCII)

    /** Returns true if [prefix] (at least 7 bytes) starts with the OpenUp encryption magic. */
    fun looksEncrypted(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    fun encrypt(
        out: OutputStream,
        key: PasswordKey,
        kdfSalt: ByteArray,
        iterations: Int = PasswordKey.DEFAULT_ITERATIONS,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): OutputStream {
        require(kdfSalt.size == PasswordKey.SALT_LEN) { "bad salt length" }
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "bad chunk size" }
        val random = SecureRandom()
        val fileSalt = ByteArray(16).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(iterations)
            .put(kdfSalt).put(fileSalt).put(noncePrefix).putInt(chunkSize)
            .array()
        val fileKey = fileKey(key.masterKey(kdfSalt, iterations), fileSalt, header)
        out.write(header)
        return EncryptingOutputStream(out, fileKey, noncePrefix, chunkSize)
    }

    /**
     * Wraps [input] for decryption. The header is read immediately; wrong passwords are detected
     * when the first chunk is read and reported as [WrongPasswordException].
     */
    fun decrypt(input: InputStream, key: PasswordKey): InputStream {
        val header = ByteArray(HEADER_LEN)
        try {
            DataInputStream(input).readFully(header)
        } catch (e: EOFException) {
            throw IOException("Encrypted file is truncated", e)
        }
        if (!looksEncrypted(header)) throw IOException("Not an OpenUp encrypted file")
        val buf = ByteBuffer.wrap(header)
        buf.position(MAGIC.size)
        val version = buf.get()
        if (version != VERSION) throw IOException("Unsupported encryption version $version")
        val kdf = buf.get()
        if (kdf != KDF_PBKDF2_SHA256) throw IOException("Unsupported key derivation $kdf")
        val iterations = buf.getInt()
        if (iterations !in PasswordKey.MIN_ITERATIONS..PasswordKey.MAX_ITERATIONS) {
            throw IOException("Corrupted encryption header")
        }
        val kdfSalt = ByteArray(16).also { buf.get(it) }
        val fileSalt = ByteArray(16).also { buf.get(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { buf.get(it) }
        val chunkSize = buf.getInt()
        if (chunkSize !in MIN_CHUNK..MAX_CHUNK) throw IOException("Corrupted encryption header")
        val fileKey = fileKey(key.masterKey(kdfSalt, iterations), fileSalt, header)
        return DecryptingInputStream(input, fileKey, noncePrefix, chunkSize)
    }

    private fun fileKey(master: ByteArray, fileSalt: ByteArray, header: ByteArray): SecretKeySpec {
        val info = INFO + header
        val raw = Hkdf.deriveSha256(master, fileSalt, info, 32)
        return SecretKeySpec(raw, "AES").also { raw.fill(0) }
    }

    private fun nonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray {
        if (counter > 0xFFFF_FFFFL) throw IOException("Stream too long for encryption format")
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, NONCE_PREFIX_LEN)
        n[7] = (counter ushr 24).toByte()
        n[8] = (counter ushr 16).toByte()
        n[9] = (counter ushr 8).toByte()
        n[10] = counter.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    private class EncryptingOutputStream(
        out: OutputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
        private val chunkSize: Int,
    ) : FilterOutputStream(out) {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val plain = ByteArray(chunkSize)
        private val sealed = ByteArray(chunkSize + TAG_LEN)
        private var filled = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) {
            if (closed) throw IOException("Stream closed")
            if (filled == chunkSize) seal(last = false)
            plain[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var o = off
            var l = len
            while (l > 0) {
                if (filled == chunkSize) {
                    // More data follows, so the buffered chunk is not the last one.
                    seal(last = false)
                }
                val n = minOf(l, chunkSize - filled)
                System.arraycopy(b, o, plain, filled, n)
                filled += n
                o += n
                l -= n
            }
        }

        private fun seal(last: Boolean) {
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, nonce(noncePrefix, counter, last)))
                val n = cipher.doFinal(plain, 0, filled, sealed, 0)
                out.write(sealed, 0, n)
            } catch (e: GeneralSecurityException) {
                throw IOException("Encryption failed", e)
            }
            counter++
            filled = 0
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                seal(last = true)
                out.flush()
            } finally {
                plain.fill(0)
                out.close()
            }
        }
    }

    private class DecryptingInputStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val sealed = ByteArray(chunkSize + TAG_LEN)
        private val plain = ByteArray(chunkSize)
        private var plainLen = 0
        private var plainPos = 0
        private var counter = 0L
        private var finished = false
        private var lookahead = -1

        override fun read(): Int {
            while (plainPos == plainLen) {
                if (finished) return -1
                nextChunk()
            }
            return plain[plainPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (plainPos == plainLen) {
                if (finished) return -1
                nextChunk()
            }
            val n = minOf(len, plainLen - plainPos)
            System.arraycopy(plain, plainPos, b, off, n)
            plainPos += n
            return n
        }

        override fun available(): Int = plainLen - plainPos

        private fun nextChunk() {
            var filled = 0
            if (lookahead >= 0) {
                sealed[0] = lookahead.toByte()
                filled = 1
                lookahead = -1
            }
            while (filled < sealed.size) {
                val n = input.read(sealed, filled, sealed.size - filled)
                if (n < 0) break
                filled += n
            }
            val last: Boolean
            if (filled < sealed.size) {
                last = true
            } else {
                val next = input.read()
                if (next < 0) {
                    last = true
                } else {
                    lookahead = next
                    last = false
                }
            }
            if (filled < TAG_LEN) throw IOException("Encrypted file is truncated")
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, nonce(noncePrefix, counter, last)))
                plainLen = cipher.doFinal(sealed, 0, filled, plain, 0)
            } catch (e: AEADBadTagException) {
                throw if (counter == 0L) {
                    WrongPasswordException()
                } else {
                    IOException("Encrypted file is corrupted or truncated (chunk $counter)", e)
                }
            } catch (e: GeneralSecurityException) {
                throw IOException("Decryption failed", e)
            }
            plainPos = 0
            counter++
            if (last) finished = true
        }

        override fun close() {
            plain.fill(0)
            input.close()
        }
    }
}

class WrongPasswordException : IOException("Wrong password, or the backup file is corrupted")
