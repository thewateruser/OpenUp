package org.openup.data

import kotlinx.serialization.Serializable
import org.openup.core.crypto.Hkdf
import org.openup.core.crypto.PasswordKey
import org.openup.core.io.EncryptedBackupException
import org.openup.core.io.EncryptionSpec
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protects meta.json of encrypted backups against changes. meta.json stays readable without the
 * password (so backups can be listed), but it decides what a restore does — which app or folder is
 * written, which permissions and special access are granted — so it is authenticated with an
 * HMAC-SHA256 whose key is derived from the backup password. The signature is stored in meta.sig.
 */
object MetaSignature {
    const val FILE = "meta.sig"
    private val INFO = "OpenUp meta.json v1".toByteArray(Charsets.US_ASCII)

    @Serializable
    data class Sig(val version: Int = 1, val kdfSalt: String, val iterations: Int, val hmac: String)

    fun sign(metaBytes: ByteArray, spec: EncryptionSpec): Sig =
        Sig(kdfSalt = BackupIO.hex(spec.kdfSalt), iterations = spec.iterations, hmac = BackupIO.hex(mac(metaBytes, spec.key, spec.kdfSalt, spec.iterations)))

    /**
     * Checks [metaBytes] before they are used for a restore.
     * - Signed: the password must be available and the signature must match.
     * - Not signed: only accepted if the metadata does not claim encrypted files.
     */
    fun verify(metaBytes: ByteArray, sig: Sig?, claimsEncrypted: Boolean, key: PasswordKey?, allowUnsigned: Boolean = false) {
        if (sig == null) {
            if (claimsEncrypted && !allowUnsigned) {
                throw IOException(
                    "This encrypted backup has no signature: it was made before OpenUp 1.1.2, or it was changed. " +
                        "To restore it anyway, turn on \"Restore backups from older versions\" in Settings."
                )
            }
            return
        }
        if (sig.version != 1) throw IOException("Unsupported backup signature version ${sig.version}")
        if (key == null) throw EncryptedBackupException()
        val salt = unhex(sig.kdfSalt) ?: throw IOException("Corrupted backup signature")
        if (salt.size != PasswordKey.SALT_LEN || sig.iterations !in PasswordKey.MIN_ITERATIONS..PasswordKey.MAX_ITERATIONS) {
            throw IOException("Corrupted backup signature")
        }
        val expected = unhex(sig.hmac) ?: throw IOException("Corrupted backup signature")
        val actual = mac(metaBytes, key, salt, sig.iterations)
        if (!MessageDigest.isEqual(expected, actual)) {
            throw IOException("This backup was changed after it was made, or the password is wrong. It is not restored.")
        }
    }

    private fun mac(data: ByteArray, key: PasswordKey, salt: ByteArray, iterations: Int): ByteArray {
        val macKey = Hkdf.deriveSha256(key.masterKey(salt, iterations), ByteArray(0), INFO, 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        macKey.fill(0)
        return mac.doFinal(data)
    }

    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
