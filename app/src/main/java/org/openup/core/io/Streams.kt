package org.openup.core.io

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Receives byte counts while data is copied. Implementations may throw to abort the copy. */
fun interface ByteProgress {
    fun onBytes(count: Long)

    companion object {
        val NONE = ByteProgress { }
    }
}

class CountingInputStream(input: InputStream, private val progress: ByteProgress = ByteProgress.NONE) :
    FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) add(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) add(s)
        return s
    }

    override fun markSupported() = false

    private fun add(n: Long) {
        count += n
        progress.onBytes(n)
    }
}

class CountingOutputStream(out: OutputStream, private val progress: ByteProgress = ByteProgress.NONE) :
    FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
        progress.onBytes(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
        progress.onBytes(len.toLong())
    }
}

/** A file output stream that fsyncs before closing, so finished backups survive power loss. */
class SyncingFileOutputStream(file: File) : FileOutputStream(file) {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            flush()
            fd.sync()
        } finally {
            super.close()
        }
    }
}

object Streams {
    fun copy(input: InputStream, output: OutputStream, progress: ByteProgress = ByteProgress.NONE): Long {
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
            progress.onBytes(n.toLong())
        }
        return total
    }

    /** Reads and discards everything left in [input]. Used to unblock a writer on the other side of a pipe. */
    fun drain(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        try {
            while (input.read(buffer) >= 0) {
                // discard
            }
        } catch (_: IOException) {
        }
    }

    fun closeQuietly(c: AutoCloseable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}
