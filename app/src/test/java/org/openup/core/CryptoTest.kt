package org.openup.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openup.core.crypto.Hkdf
import org.openup.core.crypto.PasswordKey
import org.openup.core.crypto.StreamCrypto
import org.openup.core.crypto.WrongPasswordException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

class CryptoTest {
    private val iterations = 1_000
    private val chunk = 1024
    private val salt = ByteArray(16) { it.toByte() }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfRfc5869Case1() {
        val okm = Hkdf.deriveSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun pbkdf2KnownAnswer() {
        // RFC 7914 section 11 test vector for PBKDF2-HMAC-SHA256 (first 32 bytes).
        val out = PasswordKey.pbkdf2("passwd".toCharArray(), "salt".toByteArray(), 1)
        assertArrayEquals(hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"), out)
    }

    private fun encrypt(data: ByteArray, password: String = "secret", writeInPieces: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        StreamCrypto.encrypt(bos, PasswordKey(password.toCharArray()), salt, iterations, chunk).use { out ->
            if (writeInPieces) {
                var i = 0
                val rnd = Random(1)
                while (i < data.size) {
                    if (rnd.nextInt(5) == 0) {
                        out.write(data[i].toInt()); i++
                    } else {
                        val n = minOf(data.size - i, rnd.nextInt(3000))
                        out.write(data, i, n); i += n
                    }
                }
            } else {
                out.write(data)
            }
        }
        return bos.toByteArray()
    }

    private fun decrypt(data: ByteArray, password: String = "secret"): ByteArray =
        StreamCrypto.decrypt(ByteArrayInputStream(data), PasswordKey(password.toCharArray())).use { it.readBytes() }

    @Test
    fun roundTripAllSizes() {
        val rnd = Random(3)
        for (size in listOf(0, 1, 15, 16, 17, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 7, 50_000)) {
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            for (pieces in listOf(false, true)) {
                val enc = encrypt(data, writeInPieces = pieces)
                val chunks = if (size == 0) 1 else (size + chunk - 1) / chunk
                assertEquals("size $size", StreamCrypto.HEADER_LEN + size + chunks * 16, enc.size)
                assertArrayEquals("size $size", data, decrypt(enc))
                // Single-byte reads must give the same result.
                val single = StreamCrypto.decrypt(ByteArrayInputStream(enc), PasswordKey("secret".toCharArray()))
                val collected = ByteArrayOutputStream()
                while (true) {
                    val b = single.read()
                    if (b < 0) break
                    collected.write(b)
                }
                assertArrayEquals(data, collected.toByteArray())
            }
        }
    }

    @Test
    fun ciphertextIsRandomized() {
        val data = ByteArray(100)
        val a = encrypt(data)
        val b = encrypt(data)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun wrongPassword() {
        val enc = encrypt(ByteArray(5000) { 1 })
        try {
            decrypt(enc, "wrong")
            fail("wrong password accepted")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun detectsTampering() {
        val data = ByteArray(5 * chunk + 100) { (it % 251).toByte() }
        val enc = encrypt(data)
        val positions = listOf(3, 10, 20, 40, StreamCrypto.HEADER_LEN + 5, StreamCrypto.HEADER_LEN + chunk + 30, enc.size - 1)
        for (pos in positions) {
            val bad = enc.copyOf()
            bad[pos] = (bad[pos].toInt() xor 0x01).toByte()
            try {
                decrypt(bad)
                fail("tampering at $pos not detected")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun detectsTruncationAndExtension() {
        val data = ByteArray(4 * chunk) { (it % 13).toByte() }
        val enc = encrypt(data)
        val sealedChunk = chunk + 16
        val cuts = listOf(
            StreamCrypto.HEADER_LEN,
            StreamCrypto.HEADER_LEN + sealedChunk, // cut exactly on a chunk boundary
            StreamCrypto.HEADER_LEN + 2 * sealedChunk,
            enc.size - 1,
            enc.size - 16,
        )
        for (cut in cuts) {
            try {
                decrypt(enc.copyOf(cut))
                fail("truncation at $cut not detected")
            } catch (_: IOException) {
            }
        }
        try {
            decrypt(enc + byteArrayOf(1, 2, 3))
            fail("extension not detected")
        } catch (_: IOException) {
        }
    }

    @Test
    fun detectsChunkReordering() {
        val data = ByteArray(3 * chunk) { (it / chunk).toByte() }
        val enc = encrypt(data + byteArrayOf(9))
        val sealedChunk = chunk + 16
        val swapped = enc.copyOf()
        val a = StreamCrypto.HEADER_LEN
        val b = a + sealedChunk
        System.arraycopy(enc, b, swapped, a, sealedChunk)
        System.arraycopy(enc, a, swapped, b, sealedChunk)
        try {
            decrypt(swapped)
            fail("reordering not detected")
        } catch (_: IOException) {
        }
    }
}
