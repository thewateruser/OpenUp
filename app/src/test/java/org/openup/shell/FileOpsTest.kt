package org.openup.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openup.core.archive.TarWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random

class FileOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun tree(root: File): Map<String, ByteArray> {
        val rnd = Random(1)
        val files = linkedMapOf(
            "a.txt" to "hello".toByteArray(),
            "dir/b.bin" to ByteArray(70_000).also { rnd.nextBytes(it) },
            "dir/sub/c" to ByteArray(0),
            "cache/skip.me" to "cache".toByteArray(),
            "ü nicode/名前.txt" to "x".toByteArray(),
        )
        for ((rel, data) in files) {
            val f = File(root, rel)
            f.parentFile!!.mkdirs()
            f.writeBytes(data)
        }
        File(root, "emptydir").mkdirs()
        return files
    }

    private fun archive(root: File, excludes: Set<String> = emptySet()): Pair<ByteArray, FileOps.TarStats> {
        val bos = ByteArrayOutputStream()
        val stats = FileOps.tar(root, bos, excludes)
        return bos.toByteArray() to stats
    }

    private fun contents(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }

    @Test
    fun roundTripReplacesContents() {
        val src = tmp.newFolder("src")
        val files = tree(src)
        val (data, stats) = archive(src, setOf("cache"))
        assertEquals(files.size - 1, stats.files)
        assertEquals(0, stats.skipped)

        val dest = tmp.newFolder("dest")
        File(dest, "old.txt").writeText("stale")
        File(dest, "dir").mkdirs()
        File(dest, "dir/old2").writeText("stale")
        val out = FileOps.untar(dest, ByteArrayInputStream(data))
        assertEquals(files.size - 1, out.files)

        val expected = files.filterKeys { !it.startsWith("cache/") }
        val actual = contents(dest)
        assertEquals(expected.keys, actual.keys)
        for ((k, v) in expected) assertArrayEquals(k, v, actual[k])
        assertTrue(File(dest, "emptydir").isDirectory)
        assertNull(dest.listFiles()!!.firstOrNull { it.name.startsWith(FileOps.STAGING_PREFIX) })
    }

    @Test
    fun untarConsumesWholeStream() {
        val src = tmp.newFolder("srcPad")
        tree(src)
        val (data, _) = archive(src)
        val input = ByteArrayInputStream(data + ByteArray(1024))
        FileOps.untar(tmp.newFolder("destPad"), input)
        assertEquals(0, input.available())
    }

    @Test
    fun mergeKeepsUnrelatedFilesAndOverwritesSameNames() {
        val src = tmp.newFolder("srcMerge")
        File(src, "dir").mkdirs()
        File(src, "dir/a.txt").writeText("new a")
        File(src, "top.txt").writeText("new top")
        val (data, _) = archive(src)
        val dest = tmp.newFolder("destMerge")
        File(dest, "dir").mkdirs()
        File(dest, "dir/a.txt").writeText("old a")
        File(dest, "dir/keep.txt").writeText("keep")
        File(dest, "other.txt").writeText("other")
        FileOps.untar(dest, ByteArrayInputStream(data), merge = true)
        assertEquals("new a", File(dest, "dir/a.txt").readText())
        assertEquals("keep", File(dest, "dir/keep.txt").readText())
        assertEquals("other", File(dest, "other.txt").readText())
        assertEquals("new top", File(dest, "top.txt").readText())
        assertNull(dest.listFiles()!!.firstOrNull { it.name.startsWith(FileOps.STAGING_PREFIX) })
    }

    @Test
    fun nestedExcludesAndDirectoryListing() {
        val src = tmp.newFolder("srcEx")
        File(src, "keep/inner").mkdirs()
        File(src, "keep/inner/x").writeText("x")
        File(src, "OpenUp/apps").mkdirs()
        File(src, "OpenUp/apps/big").writeText("backup")
        File(src, "a/b/skip").mkdirs()
        File(src, "a/b/skip/y").writeText("y")
        File(src, "a/b/z").writeText("z")
        val (data, stats) = archive(src, setOf("OpenUp", "a/b/skip"))
        assertEquals(2, stats.files)
        val dest = tmp.newFolder("destEx")
        FileOps.untar(dest, ByteArrayInputStream(data))
        assertEquals(setOf("keep/inner/x", "a/b/z"), contents(dest).keys)
        assertEquals(listOf("a", "keep", "OpenUp"), FileOps.listDirectories(src))
        assertTrue(FileOps.listDirectories(File(src, "missing")).isEmpty())
    }

    @Test
    fun untarCreatesMissingRoot() {
        val src = tmp.newFolder("src2")
        tree(src)
        val (data, _) = archive(src)
        val dest = File(tmp.root, "does/not/exist")
        FileOps.untar(dest, ByteArrayInputStream(data))
        assertEquals(contents(src).keys, contents(dest).keys)
    }

    @Test
    fun corruptArchiveKeepsExistingData() {
        val src = tmp.newFolder("src3")
        tree(src)
        val (data, _) = archive(src)
        val dest = tmp.newFolder("dest3")
        File(dest, "keep.txt").writeText("precious")
        try {
            FileOps.untar(dest, ByteArrayInputStream(data.copyOf(data.size / 2)))
            fail("truncated archive accepted")
        } catch (_: IOException) {
        }
        assertEquals("precious", File(dest, "keep.txt").readText())
        assertEquals(listOf("keep.txt"), dest.list()!!.toList())
    }

    @Test
    fun errorReportedAtEndOfStreamKeepsExistingData() {
        val src = tmp.newFolder("srcLate")
        tree(src)
        val (data, _) = archive(src)
        val dest = tmp.newFolder("destLate")
        File(dest, "keep.txt").writeText("precious")
        // Like the checksum check or the pipe's close status: the error only shows up at the very end.
        val failingAtEof = object : java.io.FilterInputStream(ByteArrayInputStream(data)) {
            override fun read(): Int = super.read().also { if (it < 0) throw IOException("checksum mismatch") }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it < 0) throw IOException("checksum mismatch") }
        }
        for (merge in listOf(false, true)) {
            try {
                FileOps.untar(dest, failingAtEof, merge)
                fail("late error ignored")
            } catch (e: IOException) {
                assertEquals("checksum mismatch", e.message)
            }
            assertEquals(listOf("keep.txt"), dest.list()!!.toList())
        }
    }

    @Test
    fun failedSwapIsUndoneInBothModes() {
        val src = tmp.newFolder("srcSwap")
        File(src, "dir").mkdirs()
        File(src, "dir/a.txt").writeText("new a")
        File(src, "b.txt").writeText("new b")
        File(src, "c.txt").writeText("new c")
        val (data, _) = archive(src)
        for (merge in listOf(false, true)) {
            for (failAt in 1..6) {
                val dest = tmp.newFolder("destSwap-$merge-$failAt")
                File(dest, "dir").mkdirs()
                File(dest, "dir/a.txt").writeText("old a")
                File(dest, "b.txt").writeText("old b")
                File(dest, "keep.txt").writeText("old keep")
                val before = contents(dest).mapValues { String(it.value) }
                var moves = 0
                val failing: (java.nio.file.Path, java.nio.file.Path) -> Unit = { from, to ->
                    if (++moves == failAt) throw IOException("disk full")
                    Files.move(from, to)
                }
                try {
                    FileOps.untar(dest, ByteArrayInputStream(data), merge, failing)
                    // Fewer moves than failAt: the swap simply succeeded.
                    assertEquals("new a", File(dest, "dir/a.txt").readText())
                    continue
                } catch (e: IOException) {
                    assertEquals("disk full", e.message)
                }
                assertEquals("merge=$merge failAt=$failAt", before, contents(dest).mapValues { String(it.value) })
                assertNull(dest.listFiles()!!.firstOrNull { it.name.startsWith(FileOps.STAGING_PREFIX) })
            }
        }
    }

    @Test
    fun refusesSymlinkedRestoreTarget() {
        val real = tmp.newFolder("realTarget")
        val link = File(tmp.root, "linkTarget")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val src = tmp.newFolder("srcLink")
        File(src, "x").writeText("x")
        val (data, _) = archive(src)
        try {
            FileOps.untar(link, ByteArrayInputStream(data))
            fail("followed a symlinked target")
        } catch (_: IOException) {
        }
        assertTrue(real.list()!!.isEmpty())
    }

    @Test
    fun rejectsPathTraversalAndEscapingLinks() {
        val bos = ByteArrayOutputStream()
        TarWriter(bos).use { w ->
            w.putFile("../evil.txt", 1, 0x1A4, 0, ByteArrayInputStream(byteArrayOf(1)))
            w.putFile("ok/../../evil2.txt", 1, 0x1A4, 0, ByteArrayInputStream(byteArrayOf(1)))
            w.putSymlink("link", "/etc", 0)
            w.putSymlink("link2", "../../outside", 0)
            w.putSymlink("inside", "good.txt", 0)
            w.putFile("good.txt", 2, 0x1A4, 0, ByteArrayInputStream(byteArrayOf(4, 5)))
        }
        val parent = tmp.newFolder("parent")
        val dest = File(parent, "dest").apply { mkdirs() }
        val stats = FileOps.untar(dest, ByteArrayInputStream(bos.toByteArray()))
        assertEquals(4, stats.skipped)
        assertFalse(File(parent, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertFalse(Files.exists(File(dest, "link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(byteArrayOf(4, 5), File(dest, "good.txt").readBytes())
        assertEquals("good.txt", Files.readSymbolicLink(File(dest, "inside").toPath()).toString())
    }

    @Test
    fun symlinksAreArchivedNotFollowed() {
        val src = tmp.newFolder("src4")
        File(src, "real.txt").writeText("data")
        Files.createSymbolicLink(File(src, "alias").toPath(), java.nio.file.Paths.get("real.txt"))
        val outside = tmp.newFolder("outside")
        File(outside, "secret").writeText("no")
        Files.createSymbolicLink(File(src, "escape").toPath(), outside.toPath())
        val (data, stats) = archive(src)
        assertEquals(1, stats.files)
        val dest = tmp.newFolder("dest4")
        val out = FileOps.untar(dest, ByteArrayInputStream(data))
        assertEquals(1, out.skipped) // the absolute link is not recreated
        assertEquals("data", File(dest, "alias").readText())
        assertFalse(File(dest, "escape/secret").exists())
    }

    @Test
    fun sizeAndDelete() {
        val src = tmp.newFolder("src5")
        val files = tree(src)
        assertEquals(files.values.sumOf { it.size.toLong() }, FileOps.sizeOf(src))
        assertEquals(-1, FileOps.sizeOf(File(src, "missing")))
        FileOps.deleteRecursive(src.toPath())
        assertFalse(src.exists())
    }

    @Test
    fun safeRelative() {
        assertEquals("a/b", FileOps.safeRelative("./a//b/"))
        assertNull(FileOps.safeRelative("../a"))
        assertNull(FileOps.safeRelative("a/../../b"))
        assertNull(FileOps.safeRelative(""))
        assertNull(FileOps.safeRelative(FileOps.STAGING_PREFIX + "x/y"))
        assertTrue(FileOps.linkStaysInside("a/b/link", "../c"))
        assertFalse(FileOps.linkStaysInside("link", "../c"))
    }
}
