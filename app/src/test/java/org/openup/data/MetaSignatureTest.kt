package org.openup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openup.core.crypto.PasswordKey
import org.openup.core.io.EncryptedBackupException
import org.openup.core.io.EncryptionSpec
import org.openup.data.location.LocalNode
import org.openup.data.model.ApkFile
import org.openup.data.model.AppBackupMeta
import org.openup.data.model.StoredFile
import org.openup.core.io.Compression
import java.io.File
import java.io.IOException

class MetaSignatureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val salt = ByteArray(16) { 9 }
    private fun spec(pw: String = "secret") = EncryptionSpec(PasswordKey(pw.toCharArray()), salt, 1_000)
    private fun key(pw: String = "secret") = PasswordKey(pw.toCharArray())
    private fun storage() = BackupStorage({ LocalNode(File(tmp.root, "OpenUp")) })

    private fun meta(pkg: String, encrypted: Boolean) = AppBackupMeta(
        packageName = pkg,
        label = "App",
        versionCode = 1,
        backupTime = 1,
        apks = listOf(ApkFile("base", StoredFile("apk/base.apk", 1, 1, Compression.NONE, encrypted))),
        permissions = listOf("android.permission.CAMERA"),
    )

    @Test
    fun signatureDetectsChangesAndWrongPasswords() {
        val bytes = "{\"a\":1}".toByteArray()
        val sig = MetaSignature.sign(bytes, spec())
        MetaSignature.verify(bytes, sig, claimsEncrypted = true, key = key())
        for ((data, pw) in listOf("{\"a\":2}".toByteArray() to "secret", bytes to "wrong")) {
            try {
                MetaSignature.verify(data, sig, claimsEncrypted = true, key = key(pw))
                fail("accepted changed metadata or wrong password")
            } catch (e: IOException) {
                assertTrue(e !is EncryptedBackupException)
            }
        }
        try {
            MetaSignature.verify(bytes, sig, claimsEncrypted = true, key = null)
            fail()
        } catch (_: EncryptedBackupException) {
        }
    }

    @Test
    fun unsignedMetadataOnlyAllowedForUnencryptedBackups() {
        MetaSignature.verify(ByteArray(3), null, claimsEncrypted = false, key = null)
        try {
            MetaSignature.verify(ByteArray(3), null, claimsEncrypted = true, key = key())
            fail("unsigned encrypted backup accepted")
        } catch (_: IOException) {
        }
        // Explicit opt-in for backups made before signatures existed.
        MetaSignature.verify(ByteArray(3), null, claimsEncrypted = true, key = key(), allowUnsigned = true)
    }

    @Test
    fun restoreLoadsOnlyUntamperedMetadata() {
        val s = storage()
        val dir = s.appDir("com.example").child("20260101-000000").apply { mkdirs() }
        s.writeAppMeta(dir, meta("com.example", encrypted = true), spec())
        assertTrue(s.isSigned(dir))
        assertEquals("com.example", s.loadAppBackupForRestore(dir, key(), "com.example").meta.packageName)

        // Someone with access to the backup folder adds a permission.
        val metaFile = File((dir as LocalNode).file, "meta.json")
        metaFile.writeText(metaFile.readText().replace("android.permission.CAMERA", "android.permission.READ_SMS"))
        try {
            s.loadAppBackupForRestore(dir, key(), "com.example")
            fail("tampered metadata accepted")
        } catch (_: IOException) {
        }

        // Removing the signature does not help either, because the files are encrypted.
        s.writeAppMeta(dir, meta("com.example", encrypted = true), spec())
        File(dir.file, MetaSignature.FILE).delete()
        try {
            s.loadAppBackupForRestore(dir, key(), "com.example")
            fail("unsigned encrypted backup accepted")
        } catch (_: IOException) {
        }
    }

    @Test
    fun packageMustMatchItsFolder() {
        val s = storage()
        val dir = s.appDir("com.game").child("20260101-000000").apply { mkdirs() }
        s.writeAppMeta(dir, meta("com.bank", encrypted = false), null)
        assertFalse(s.isSigned(dir))
        assertEquals(emptyList<Any>(), s.appBackups("com.game"))
        try {
            s.loadAppBackupForRestore(dir, null, "com.game")
            fail("backup for another package accepted")
        } catch (_: IOException) {
        }
    }
}
