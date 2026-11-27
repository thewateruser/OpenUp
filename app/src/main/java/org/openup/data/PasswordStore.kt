package org.openup.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openup.core.crypto.PasswordKey
import org.openup.core.io.EncryptionSpec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the backup password so scheduled backups can encrypt without user interaction.
 * The password is encrypted with a non-exportable Android Keystore key.
 */
class PasswordStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _hasPassword = MutableStateFlow(prefs.contains(KEY_PASSWORD))
    val hasPassword: StateFlow<Boolean> = _hasPassword.asStateFlow()

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun setPassword(password: String) {
        require(password.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        prefs.edit()
            .putString(KEY_PASSWORD, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString(KEY_SALT, Base64.encodeToString(PasswordKey.newSalt(), Base64.NO_WRAP))
            .apply()
        _hasPassword.value = true
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PASSWORD).remove(KEY_SALT).apply()
        _hasPassword.value = false
    }

    /** Returns the stored password, or null if none is set or it can no longer be decrypted. */
    @Synchronized
    fun getPassword(): String? {
        val stored = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun salt(): ByteArray? = prefs.getString(KEY_SALT, null)?.let {
        runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()?.takeIf { s -> s.size == PasswordKey.SALT_LEN }
    }

    /**
     * Key material for one job, or null when encryption is off. Throws if a password is configured
     * but can no longer be read, so backups are never silently written unencrypted.
     */
    fun session(): CryptoSession? {
        if (!prefs.contains(KEY_PASSWORD)) return null
        val pw = getPassword()
        val salt = salt()
        if (pw == null || salt == null) {
            throw java.io.IOException("The backup password can no longer be read on this device. Set it again in Settings.")
        }
        val key = PasswordKey(pw.toCharArray())
        return CryptoSession(key, EncryptionSpec(key, salt))
    }

    companion object {
        private const val ALIAS = "openup_backup_password"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SALT = "kdf_salt"
    }
}

/** Keys used by one job: [key] decrypts any backup made with the password, [spec] encrypts new ones. */
class CryptoSession(val key: PasswordKey, val spec: EncryptionSpec)
