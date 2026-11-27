package org.openup.core.crypto

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns the user's backup password into 256-bit master keys.
 *
 * PBKDF2 is deliberately slow, so derived keys are cached per (salt, iterations) for the lifetime
 * of this object. One instance is created per backup/restore job.
 */
class PasswordKey(password: CharArray) {
    private val password: CharArray = password.copyOf()
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun masterKey(salt: ByteArray, iterations: Int): ByteArray {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "unsupported PBKDF2 iteration count" }
        val cacheKey = salt.joinToString("") { "%02x".format(it) } + ":" + iterations
        return cache.getOrPut(cacheKey) { pbkdf2(password, salt, iterations) }
    }

    fun wipe() {
        password.fill('\u0000')
        cache.values.forEach { it.fill(0) }
        cache.clear()
    }

    companion object {
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_ITERATIONS = 1_000
        const val MAX_ITERATIONS = 10_000_000
        const val SALT_LEN = 16

        fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }

        fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, iterations, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}
