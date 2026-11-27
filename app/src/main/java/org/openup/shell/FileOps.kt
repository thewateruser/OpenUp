package org.openup.shell

import org.openup.core.archive.TarEntry
import org.openup.core.archive.TarReader
import org.openup.core.archive.TarWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/**
 * File tree operations executed with shell privileges (inside the Shizuku user service).
 * Uses only java.nio so it behaves the same on Android and on the JVM (unit tests).
 */
object FileOps {
    const val STAGING_PREFIX = ".openup-restore-"
    private const val MAX_REPORTED_PATHS = 50

    class TarStats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var skipped = 0
        var shrunk = 0
        val skippedPaths = ArrayList<String>()

        fun skip(path: String, reason: String) {
            skipped++
            if (skippedPaths.size < MAX_REPORTED_PATHS) skippedPaths.add("$path ($reason)")
        }
    }

    private fun attrs(p: Path): BasicFileAttributes =
        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    /**
     * Archives the contents of [root] (not the directory itself) into [out]. Does not close [out].
     * [excludes] are paths relative to [root] (e.g. "cache" or "Android/data") that are skipped.
     */
    fun tar(root: File, out: OutputStream, excludes: Set<String>): TarStats {
        val stats = TarStats()
        val rootPath = root.toPath()
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) throw IOException("Not a directory: $root")
        val writer = TarWriter(BufferedOutputStream(out, 256 * 1024))
        walk(rootPath, "", writer, excludes, stats)
        writer.finish()
        stats.shrunk = writer.shrunkFiles
        return stats
    }

    private fun walk(dir: Path, rel: String, writer: TarWriter, excludes: Set<String>, stats: TarStats) {
        val children = try {
            Files.newDirectoryStream(dir).use { s -> s.map { it.fileName.toString() }.sorted() }
        } catch (e: IOException) {
            if (rel.isEmpty()) throw IOException("Cannot read $dir: ${e.message}", e)
            stats.skip(rel, "cannot list: ${e.javaClass.simpleName}")
            return
        }
        for (name in children) {
            if ((if (rel.isEmpty()) name else "$rel/$name") in excludes) continue
            if (rel.isEmpty() && name.startsWith(STAGING_PREFIX)) continue
            val path = dir.resolve(name)
            val childRel = if (rel.isEmpty()) name else "$rel/$name"
            val a = try {
                attrs(path)
            } catch (e: IOException) {
                stats.skip(childRel, "cannot stat: ${e.javaClass.simpleName}")
                continue
            }
            val mtime = a.lastModifiedTime().toMillis() / 1000
            when {
                a.isSymbolicLink -> {
                    val target = try {
                        Files.readSymbolicLink(path).toString()
                    } catch (e: IOException) {
                        stats.skip(childRel, "cannot read link")
                        continue
                    }
                    writer.putSymlink(childRel, target, mtime)
                }
                a.isDirectory -> {
                    writer.putDirectory(childRel, 0x1ED, mtime)
                    stats.dirs++
                    walk(path, childRel, writer, excludes, stats)
                }
                a.isRegularFile -> {
                    val input = try {
                        Files.newInputStream(path)
                    } catch (e: IOException) {
                        stats.skip(childRel, "cannot open: ${e.javaClass.simpleName}")
                        continue
                    }
                    input.use {
                        // Read errors in the middle of a file abort the whole backup: the archive
                        // would otherwise contain a silently corrupted file.
                        writer.putFile(childRel, a.size(), 0x1A4, mtime, it)
                    }
                    stats.files++
                    stats.bytes += a.size()
                }
                else -> stats.skip(childRel, "special file")
            }
        }
    }

    /**
     * Restores the archive from [input] into [root]. Extraction happens in a staging directory
     * inside [root], and [root] is only touched once the archive was read fully.
     * Without [merge] the old contents are replaced; with [merge] files from the archive overwrite
     * files of the same name and everything else in [root] is kept.
     */
    fun untar(root: File, input: InputStream, merge: Boolean = false): TarStats = untar(root, input, merge, ::movePath)

    /** [mover] moves one entry to a path that does not exist yet (replaceable in tests). */
    internal fun untar(root: File, input: InputStream, merge: Boolean, mover: (Path, Path) -> Unit): TarStats {
        val stats = TarStats()
        val rootPath = root.toPath()
        if (Files.isSymbolicLink(rootPath)) throw IOException("Restore target $root is a link; refusing to follow it")
        if (!Files.isDirectory(rootPath)) {
            Files.createDirectories(rootPath)
        }
        val id = UUID.randomUUID().toString().substring(0, 8)
        val stage = rootPath.resolve(STAGING_PREFIX + id)
        Files.createDirectory(stage)
        try {
            extract(stage, input, stats)
        } catch (t: Throwable) {
            deleteQuietly(stage)
            throw t
        }
        // The archive is complete and valid: swap the contents. Every step is recorded so that a
        // failure half way (e.g. storage full) can be undone and the previous data is never lost.
        val saved = rootPath.resolve(STAGING_PREFIX + "old-" + id)
        val journal = ArrayList<SwapStep>()
        try {
            Files.createDirectory(saved)
            if (merge) {
                mergeInto(stage, rootPath, saved, journal, mover)
            } else {
                Files.newDirectoryStream(rootPath).use { s -> s.toList() }
                    .filter { it != stage && it != saved }
                    .forEach { child ->
                        val keep = saved.resolve(child.fileName.toString())
                        mover(child, keep)
                        journal.add(SwapStep.MovedAside(child, keep))
                    }
                Files.newDirectoryStream(stage).use { s -> s.toList() }.forEach { child ->
                    val target = rootPath.resolve(child.fileName.toString())
                    mover(child, target)
                    journal.add(SwapStep.Added(target))
                }
            }
        } catch (t: Throwable) {
            val undone = rollback(journal)
            deleteQuietly(stage)
            if (undone) {
                deleteQuietly(saved)
                throw t
            }
            throw IOException("Restore failed and could not be fully undone; the previous data was kept in $saved", t)
        }
        deleteQuietly(saved)
        deleteQuietly(stage)
        return stats
    }

    private sealed interface SwapStep {
        /** [original] was moved to [savedAt] to make room. */
        data class MovedAside(val original: Path, val savedAt: Path) : SwapStep

        /** [target] was created by the restore. */
        data class Added(val target: Path) : SwapStep
    }

    /** Undoes [journal] in reverse order. Returns false if something could not be put back. */
    private fun rollback(journal: List<SwapStep>): Boolean {
        var ok = true
        for (step in journal.asReversed()) {
            try {
                when (step) {
                    is SwapStep.Added -> deleteRecursive(step.target)
                    is SwapStep.MovedAside -> {
                        deleteRecursive(step.original)
                        movePath(step.savedAt, step.original)
                    }
                }
            } catch (_: Throwable) {
                ok = false
            }
        }
        return ok
    }

    private fun deleteQuietly(p: Path) {
        try {
            deleteRecursive(p)
        } catch (_: IOException) {
        }
    }

    /** Moves [src] to [dest] (which must not exist), copying if a plain rename is not possible. */
    private fun movePath(src: Path, dest: Path) {
        try {
            Files.move(src, dest)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            throw e
        } catch (e: IOException) {
            copyRecursive(src, dest)
            deleteRecursive(src)
        }
    }

    /**
     * Moves everything from [src] into [dest], merging directories. Entries that get replaced are
     * first moved to the same relative place below [saved].
     */
    private fun mergeInto(src: Path, dest: Path, saved: Path, journal: MutableList<SwapStep>, mover: (Path, Path) -> Unit) {
        Files.newDirectoryStream(src).use { s -> s.toList() }.forEach { child ->
            val name = child.fileName.toString()
            val target = dest.resolve(name)
            if (attrs(child).isDirectory && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                mergeInto(child, target, saved.resolve(name), journal, mover)
            } else {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    val keep = saved.resolve(name)
                    Files.createDirectories(saved)
                    mover(target, keep)
                    journal.add(SwapStep.MovedAside(target, keep))
                }
                mover(child, target)
                journal.add(SwapStep.Added(target))
            }
        }
    }

    /** Names of the sub-directories of [dir] (symlinks are not followed), sorted for display. */
    fun listDirectories(dir: File): List<String> {
        val p = dir.toPath()
        if (!Files.isDirectory(p)) return emptyList()
        return Files.newDirectoryStream(p).use { s ->
            s.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.map { it.fileName.toString() }
        }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun extract(stage: Path, input: InputStream, stats: TarStats) {
        val reader = TarReader(BufferedInputStream(input, 256 * 1024))
        val links = ArrayList<Pair<Path, String>>()
        val dirTimes = ArrayList<Pair<Path, Long>>()
        while (true) {
            val entry = reader.nextEntry()
            if (entry == null) {
                // Consume the trailing padding so the writer on the other end of the pipe finishes cleanly.
                reader.readToEnd()
                break
            }
            val rel = safeRelative(entry.name)
            if (rel == null) {
                stats.skip(entry.name, "unsafe path")
                continue
            }
            val target = stage.resolve(rel)
            when (entry.type) {
                TarEntry.Type.DIRECTORY -> {
                    ensureDirectory(target)
                    dirTimes.add(target to entry.mtimeSeconds)
                    stats.dirs++
                }
                TarEntry.Type.FILE -> {
                    target.parent?.let { ensureDirectory(it) }
                    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) deleteRecursive(target)
                    Files.newOutputStream(target).use { out ->
                        val buf = ByteArray(128 * 1024)
                        val src = reader.entryStream()
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            stats.bytes += n
                        }
                    }
                    setTime(target, entry.mtimeSeconds)
                    stats.files++
                }
                TarEntry.Type.SYMLINK -> links.add(target to entry.linkTarget)
                TarEntry.Type.OTHER -> stats.skip(entry.name, "unsupported entry type")
            }
        }
        // Symlinks are created last so that no archive entry can be written through one.
        for ((path, link) in links) {
            val rel = stage.relativize(path).toString()
            if (link.startsWith("/") || !linkStaysInside(rel, link)) {
                stats.skip(rel, "symlink points outside the backup")
                continue
            }
            try {
                path.parent?.let { ensureDirectory(it) }
                Files.deleteIfExists(path)
                Files.createSymbolicLink(path, java.nio.file.Paths.get(link))
            } catch (e: Exception) {
                stats.skip(rel, "cannot create symlink")
            }
        }
        for ((dir, time) in dirTimes.asReversed()) setTime(dir, time)
    }

    private fun setTime(p: Path, seconds: Long) {
        if (seconds <= 0) return
        try {
            Files.setLastModifiedTime(p, FileTime.fromMillis(seconds * 1000))
        } catch (_: Exception) {
            // Not all file systems (e.g. FUSE on some devices) allow this; it is not essential.
        }
    }

    private fun ensureDirectory(p: Path) {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.delete(p)
        Files.createDirectories(p)
    }

    internal fun safeRelative(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        if (parts[0].startsWith(STAGING_PREFIX)) return null
        return parts.joinToString("/")
    }

    internal fun linkStaysInside(linkRelPath: String, target: String): Boolean {
        val depth = ArrayList(linkRelPath.split('/').dropLast(1))
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (depth.isEmpty()) return false else depth.removeAt(depth.size - 1)
                else -> depth.add(part)
            }
        }
        return true
    }

    /** Size of all regular files below [file], or -1 if it does not exist. Symlinks are not followed. */
    fun sizeOf(file: File): Long {
        val p = file.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return -1
        var total = 0L
        Files.walkFileTree(p, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(f: Path, a: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (a.isRegularFile) total += a.size()
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, e: IOException) = java.nio.file.FileVisitResult.CONTINUE
        })
        return total
    }

    fun deleteRecursive(p: Path) {
        val a = try {
            attrs(p)
        } catch (_: NoSuchFileException) {
            return
        }
        if (a.isDirectory) {
            Files.newDirectoryStream(p).use { s -> s.toList() }.forEach { deleteRecursive(it) }
        }
        try {
            Files.delete(p)
        } catch (_: NoSuchFileException) {
        } catch (e: DirectoryNotEmptyException) {
            throw IOException("Could not empty $p", e)
        }
    }

    private fun copyRecursive(src: Path, dest: Path) {
        val a = attrs(src)
        when {
            a.isDirectory -> {
                ensureDirectory(dest)
                Files.newDirectoryStream(src).use { s -> s.toList() }.forEach {
                    copyRecursive(it, dest.resolve(it.fileName.toString()))
                }
            }
            a.isSymbolicLink -> Files.createSymbolicLink(dest, Files.readSymbolicLink(src))
            else -> Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
