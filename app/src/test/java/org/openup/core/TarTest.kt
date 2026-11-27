package org.openup.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openup.core.archive.TarEntry
import org.openup.core.archive.TarException
import org.openup.core.archive.TarReader
import org.openup.core.archive.TarWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

class TarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private data class Item(val name: String, val type: TarEntry.Type, val data: ByteArray = ByteArray(0), val link: String = "")

    private fun sampleItems(): List<Item> {
        val rnd = Random(42)
        fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        val longDir = "a".repeat(60) + "/" + "b".repeat(60)
        return listOf(
            Item("files", TarEntry.Type.DIRECTORY),
            Item("files/empty.bin", TarEntry.Type.FILE, ByteArray(0)),
            Item("files/one.bin", TarEntry.Type.FILE, bytes(1)),
            Item("files/511.bin", TarEntry.Type.FILE, bytes(511)),
            Item("files/512.bin", TarEntry.Type.FILE, bytes(512)),
            Item("files/513.bin", TarEntry.Type.FILE, bytes(513)),
            Item("files/big.bin", TarEntry.Type.FILE, bytes(300_000)),
            Item(longDir, TarEntry.Type.DIRECTORY),
            Item("$longDir/" + "c".repeat(120) + ".txt", TarEntry.Type.FILE, "long name".toByteArray()),
            Item("files/ünïcødé 名前 🙂.txt", TarEntry.Type.FILE, "unicode".toByteArray()),
            Item("files/link", TarEntry.Type.SYMLINK, link = "one.bin"),
            Item("files/longlink", TarEntry.Type.SYMLINK, link = "../" + "x".repeat(150)),
        )
    }

    private fun write(items: List<Item>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarWriter(bos).use { w ->
            for (it in items) when (it.type) {
                TarEntry.Type.DIRECTORY -> w.putDirectory(it.name, 0x1C0, 1_700_000_000)
                TarEntry.Type.FILE -> w.putFile(it.name, it.data.size.toLong(), 0x180, 1_700_000_001, ByteArrayInputStream(it.data))
                TarEntry.Type.SYMLINK -> w.putSymlink(it.name, it.link, 1_700_000_002)
                TarEntry.Type.OTHER -> error("unsupported")
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun roundTrip() {
        val items = sampleItems()
        val archive = write(items)
        assertEquals(0, archive.size % 512)
        val reader = TarReader(ByteArrayInputStream(archive))
        for (item in items) {
            val e = reader.nextEntry()!!
            assertEquals(item.name, e.name)
            assertEquals(item.type, e.type)
            when (item.type) {
                TarEntry.Type.FILE -> {
                    assertEquals(item.data.size.toLong(), e.size)
                    assertArrayEquals(item.data, reader.entryStream().readBytes())
                    assertEquals(0x180, e.mode)
                    assertEquals(1_700_000_001, e.mtimeSeconds)
                }
                TarEntry.Type.SYMLINK -> assertEquals(item.link, e.linkTarget)
                TarEntry.Type.DIRECTORY -> assertEquals(0x1C0, e.mode)
                else -> Unit
            }
        }
        assertNull(reader.nextEntry())
        assertNull(reader.nextEntry())
    }

    @Test
    fun skipsUnreadContent() {
        val items = sampleItems()
        val reader = TarReader(ByteArrayInputStream(write(items)))
        var count = 0
        while (true) {
            val e = reader.nextEntry() ?: break
            if (e.name.endsWith("513.bin")) {
                // Partially read one entry; the reader must still find the next header.
                reader.entryStream().read(ByteArray(10))
            }
            count++
        }
        assertEquals(items.size, count)
    }

    @Test
    fun detectsTruncation() {
        val archive = write(sampleItems())
        // Cutting inside the trailing zero blocks loses no data, so only cut before the end marker.
        val cuts = listOf(1, 100, 511, 512, 700, 1024, archive.size / 2, archive.size - 1025, archive.size - 1024)
        for (cut in cuts) {
            val reader = TarReader(ByteArrayInputStream(archive.copyOf(cut)))
            try {
                while (true) {
                    reader.nextEntry() ?: break
                    reader.entryStream().readBytes()
                }
                fail("truncation at $cut was not detected")
            } catch (_: TarException) {
            }
        }
    }

    @Test
    fun readToEndConsumesTrailingData() {
        val archive = write(sampleItems()) + ByteArray(5000) { 1 }
        val input = ByteArrayInputStream(archive)
        val reader = TarReader(input)
        while (reader.nextEntry() != null) {
            // skip
        }
        reader.readToEnd()
        assertEquals(0, input.available())
    }

    @Test
    fun detectsCorruptHeader() {
        val archive = write(sampleItems())
        archive[512 * 2 + 3] = (archive[512 * 2 + 3] + 1).toByte()
        val reader = TarReader(ByteArrayInputStream(archive))
        try {
            while (true) {
                reader.nextEntry() ?: break
            }
            fail("corruption not detected")
        } catch (e: TarException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }

    @Test
    fun shrinkingFileIsZeroPadded() {
        val bos = ByteArrayOutputStream()
        val w = TarWriter(bos)
        val taken = w.putFile("f", 1000, 0x1A4, 0, ByteArrayInputStream(ByteArray(400) { 7 }))
        w.putFile("g", 3, 0x1A4, 0, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        w.close()
        assertEquals(400, taken)
        assertEquals(1, w.shrunkFiles)
        val r = TarReader(ByteArrayInputStream(bos.toByteArray()))
        val data = r.nextEntry()!!.let { r.entryStream().readBytes() }
        assertEquals(1000, data.size)
        assertTrue(data.copyOfRange(0, 400).all { it == 7.toByte() })
        assertTrue(data.copyOfRange(400, 1000).all { it == 0.toByte() })
        assertEquals("g", r.nextEntry()!!.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.entryStream().readBytes())
        assertNull(r.nextEntry())
    }

    @Test
    fun paxLengthBoundaries() {
        // Record lengths around digit-count boundaries must be self consistent.
        for (n in 80..1100) {
            val value = "v".repeat(n)
            val payload = TarWriter.encodePax(mapOf("path" to value))
            val text = String(payload)
            val len = text.substringBefore(' ').toInt()
            assertEquals(payload.size, len)
        }
    }

    private fun run(vararg cmd: String, dir: File) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue("timeout", p.waitFor(60, TimeUnit.SECONDS))
        assertEquals("${cmd.joinToString(" ")} failed: $out", 0, p.exitValue())
    }

    @Test
    fun systemTarReadsOurArchives() {
        val items = sampleItems()
        val archiveFile = tmp.newFile("ours.tar")
        archiveFile.writeBytes(write(items))
        val outDir = tmp.newFolder("extract")
        run("tar", "-xf", archiveFile.absolutePath, dir = outDir)
        for (item in items) {
            val f = File(outDir, item.name)
            when (item.type) {
                TarEntry.Type.FILE -> assertArrayEquals(item.name, item.data, f.readBytes())
                TarEntry.Type.DIRECTORY -> assertTrue(f.isDirectory)
                TarEntry.Type.SYMLINK -> assertEquals(item.link, java.nio.file.Files.readSymbolicLink(f.toPath()).toString())
                else -> Unit
            }
        }
    }

    @Test
    fun readsSystemTarArchives() {
        val src = tmp.newFolder("src")
        val rnd = Random(7)
        val deep = File(src, "d".repeat(90) + "/" + "e".repeat(90)).apply { mkdirs() }
        val files = mapOf(
            File(deep, "f".repeat(110)) to ByteArray(5000).also { rnd.nextBytes(it) },
            File(src, "small.txt") to "hello".toByteArray(),
            File(src, "ü名.txt") to "x".toByteArray(),
        )
        files.forEach { (f, d) -> f.writeBytes(d) }
        for (format in listOf("gnu", "pax", "ustar")) {
            val archive = File(tmp.root, "sys-$format.tar")
            // ustar cannot store the long name; only check that format with short names.
            val target = if (format == "ustar") "small.txt" else "."
            run("tar", "--format=$format", "-cf", archive.absolutePath, target, dir = src)
            val reader = TarReader(archive.inputStream())
            val seen = HashMap<String, ByteArray>()
            while (true) {
                val e = reader.nextEntry() ?: break
                if (e.isFile) seen[e.name] = reader.entryStream().readBytes()
            }
            for ((f, d) in files) {
                val rel = f.relativeTo(src).path
                if (format == "ustar" && rel != "small.txt") continue
                assertArrayEquals("$format $rel", d, seen[rel])
            }
        }
    }
}
