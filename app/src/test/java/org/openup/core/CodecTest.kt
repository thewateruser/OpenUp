package org.openup.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import org.openup.core.crypto.PasswordKey
import org.openup.core.io.Codec
import org.openup.core.io.Compression
import org.openup.core.io.EncryptedBackupException
import org.openup.core.io.EncryptionSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

class CodecTest {
    private val salt = ByteArray(16) { 5 }

    private fun spec(pw: String) = EncryptionSpec(PasswordKey(pw.toCharArray()), salt, 1_000)

    private fun encode(data: ByteArray, compression: Compression, encryption: EncryptionSpec?): ByteArray {
        val bos = ByteArrayOutputStream()
        Codec.encoder(bos, compression, 3, encryption).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun decode(data: ByteArray, compression: Compression, encrypted: Boolean, pw: String?): ByteArray =
        Codec.decoder(ByteArrayInputStream(data), compression, encrypted, pw?.let { PasswordKey(it.toCharArray()) })
            .use { it.readBytes() }

    @Test
    fun allCombinationsRoundTrip() {
        val rnd = Random(9)
        val samples = listOf(
            ByteArray(0),
            ByteArray(1) { 4 },
            ByteArray(3_000_000) { (it % 7).toByte() },
            ByteArray(2_500_000).also { rnd.nextBytes(it) },
        )
        for (data in samples) for (c in Compression.entries) for (enc in listOf(false, true)) {
            val encoded = encode(data, c, if (enc) spec("pw") else null)
            assertArrayEquals("$c $enc ${data.size}", data, decode(encoded, c, enc, if (enc) "pw" else null))
        }
    }

    @Test
    fun missingPasswordIsReported() {
        val encoded = encode(ByteArray(10), Compression.ZSTD, spec("pw"))
        try {
            decode(encoded, Compression.ZSTD, true, null)
            fail()
        } catch (_: EncryptedBackupException) {
        }
    }

    @Test
    fun mismatchedMetadataIsReported() {
        val plain = encode(ByteArray(10), Compression.NONE, null)
        try {
            decode(plain, Compression.NONE, true, "pw")
            fail()
        } catch (_: IOException) {
        }
        try {
            decode(plain, Compression.ZSTD, false, null)
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun plainDataThatLooksEncryptedIsNotRejected() {
        // Unencrypted data may legitimately start with the encryption magic (e.g. a tar entry name).
        val data = "OPENUPE-not-really-encrypted".toByteArray()
        val encoded = encode(data, Compression.NONE, null)
        assertArrayEquals(data, decode(encoded, Compression.NONE, false, null))
    }

    @Test
    fun truncatedZstdIsReported() {
        val data = ByteArray(500_000).also { Random(1).nextBytes(it) }
        val encoded = encode(data, Compression.ZSTD, null)
        for (cut in listOf(10, encoded.size / 2, encoded.size - 5)) {
            try {
                decode(encoded.copyOf(cut), Compression.ZSTD, false, null)
                fail("truncation at $cut not detected")
            } catch (_: IOException) {
            }
        }
    }
}
