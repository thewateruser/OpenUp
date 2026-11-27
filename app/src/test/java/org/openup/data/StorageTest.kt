package org.openup.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openup.core.crypto.PasswordKey
import org.openup.core.io.Compression
import org.openup.core.io.EncryptionSpec
import org.openup.data.model.ApkFile
import org.openup.data.model.AppBackupMeta
import org.openup.data.model.DataPart
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class StorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun storage() = BackupStorage { File(tmp.root, "OpenUp").path }

    private fun meta(pkg: String, time: Long, apk: ApkFile? = null, ext: DataPart? = null) = AppBackupMeta(
        packageName = pkg,
        label = "Label $pkg",
        versionCode = 42,
        backupTime = time,
        apks = listOfNotNull(apk),
        extData = ext,
    )

    @Test
    fun partialBackupsAreInvisibleUntilCommitted() {
        val s = storage()
        s.ensureRoot()
        assertTrue(File(s.root, ".nomedia").exists())
        val (id, partial) = s.newPartial(s.appDir("com.example"))
        assertTrue(partial.name.endsWith(BackupStorage.PARTIAL))
        s.writeAppMeta(partial, meta("com.example", 1))
        assertTrue(s.appBackups("com.example").isEmpty())
        s.commitPartial(partial, id)
        val list = s.appBackups("com.example")
        assertEquals(1, list.size)
        assertEquals("Label com.example", list[0].meta.label)
        assertEquals(42, list[0].meta.versionCode)
    }

    @Test
    fun newPartialRemovesStaleLeftovers() {
        val s = storage()
        val dir = s.appDir("com.example")
        File(dir, "20200101-000000.partial/junk").apply { parentFile!!.mkdirs(); writeText("x") }
        s.newPartial(dir)
        assertFalse(File(dir, "20200101-000000.partial").exists())
    }

    @Test
    fun pruneKeepsNewest() {
        val s = storage()
        val dir = s.appDir("com.example")
        val ids = listOf("20240101-000000", "20250101-000000", "20230101-000000", "20260101-000000")
        for (id in ids) {
            val d = File(dir, id).apply { mkdirs() }
            s.writeAppMeta(d, meta("com.example", id.take(4).toLong()))
        }
        s.prune(dir, 2)
        assertEquals(listOf("20260101-000000", "20250101-000000"), s.appBackups("com.example").map { it.id })
        val latest = s.latestAppBackups()
        assertEquals("20260101-000000", latest["com.example"]!!.id)
        s.delete(File(dir, "20260101-000000"))
        s.delete(File(dir, "20250101-000000"))
        assertFalse(dir.exists())
    }

    @Test
    fun folderBackupsArePerFolder() {
        val s = storage()
        val a = "/storage/emulated/0/Documents"
        val b = "/storage/emulated/0/Download"
        assertTrue(s.folderDir(a) != s.folderDir(b))
        assertEquals(s.folderDir(a), s.folderDir(a))
        assertTrue(BackupStorage.isSafeName(BackupStorage.folderKey("/storage/emulated/0/a b/ü/..")))
        for ((path, ids) in listOf(a to listOf("20250101-000000", "20260101-000000"), b to listOf("20240101-000000"))) {
            for (id in ids) {
                val d = File(s.folderDir(path), id).apply { mkdirs() }
                s.writeSpecialMeta(d, org.openup.data.model.SpecialBackupMeta(
                    type = org.openup.data.model.FolderBackup.TYPE,
                    backupTime = id.take(4).toLong(),
                    itemCount = 1,
                    summary = "1 file",
                    files = emptyList(),
                    extras = mapOf(org.openup.data.model.FolderBackup.FOLDER_PATH_KEY to path),
                ))
            }
        }
        val all = s.folderBackups()
        assertEquals(listOf(a, a, b), all.map { it.path })
        s.prune(s.folderDir(a), 1)
        assertEquals(listOf(a, b), s.folderBackups().map { it.path })
    }

    @Test
    fun unreadableMetadataIsIgnored() {
        val s = storage()
        val dir = s.appDir("com.example")
        File(dir, "20240101-000000").mkdirs()
        File(dir, "20240101-000000/meta.json").writeText("{ not json")
        assertTrue(s.appBackups("com.example").isEmpty())
    }

    @Test
    fun rejectsUnsafePackageNames() {
        val s = storage()
        for (bad in listOf("", ".", "..", "a/b")) {
            try {
                s.appDir(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(s.appBackups("../x").isEmpty())
    }

    @Test
    fun storedFilesRoundTripAndDetectCorruption() {
        val dir = tmp.newFolder("b")
        val data = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
        val spec = EncryptionSpec(PasswordKey("pw".toCharArray()), ByteArray(16) { 1 }, 1_000)
        for (compression in Compression.entries) for (enc in listOf<EncryptionSpec?>(null, spec)) {
            val f = File(dir, "x-$compression-${enc != null}")
            val stored = BackupIO.write(f, compression, 3, enc) { it.write(data) }
            assertEquals(data.size.toLong(), stored.originalSize)
            assertEquals(f.length(), stored.storedSize)
            assertNotNull(stored.sha256)
            val key = if (enc != null) PasswordKey("pw".toCharArray()) else null
            assertArrayEquals(data, BackupIO.open(dir, stored, key).use { it.readBytes() })

            // Flip one byte in the middle: must never be returned silently.
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(f.length() / 2)
                val b = raf.read()
                raf.seek(f.length() / 2)
                raf.write(b xor 0xFF)
            }
            try {
                BackupIO.open(dir, stored, key).use { it.readBytes() }
                fail("corruption not detected for $compression encrypted=${enc != null}")
            } catch (_: IOException) {
            }
            // A truncated copy is rejected before reading.
            RandomAccessFile(f, "rw").use { it.setLength(f.length() - 1) }
            try {
                BackupIO.open(dir, stored, key)
                fail("size mismatch not detected")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun failedWriteLeavesNoFile() {
        val dir = tmp.newFolder("c")
        val f = File(dir, "partial.bin")
        try {
            BackupIO.write(f, Compression.ZSTD, 3, null) {
                it.write(ByteArray(10))
                throw IOException("source failed")
            }
            fail()
        } catch (_: IOException) {
        }
        assertFalse(f.exists())
    }
}
