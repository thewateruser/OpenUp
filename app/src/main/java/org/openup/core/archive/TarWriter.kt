package org.openup.core.archive

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Streaming writer for POSIX (pax/ustar) tar archives.
 *
 * The writer never seeks, so it can write straight into a pipe. The caller is expected to pass a
 * buffered stream. [finish] writes the end-of-archive marker without closing the underlying stream.
 */
class TarWriter(private val out: OutputStream) : Closeable {
    private var finished = false
    private val zeroBlock = ByteArray(TarFormat.BLOCK)
    private val copyBuffer = ByteArray(64 * 1024)

    /** Number of files whose content changed size while they were being archived. */
    var shrunkFiles = 0
        private set

    fun putDirectory(name: String, mode: Int = 0x1ED, mtimeSeconds: Long = 0) {
        val clean = normalize(name)
        require(clean.isNotEmpty()) { "directory entry needs a name" }
        writeHeader("$clean/", TarFormat.TYPE_DIR, 0, mode, mtimeSeconds, "")
    }

    fun putSymlink(name: String, target: String, mtimeSeconds: Long = 0) {
        val clean = normalize(name)
        require(clean.isNotEmpty()) { "symlink entry needs a name" }
        writeHeader(clean, TarFormat.TYPE_SYMLINK, 0, 0x1FF, mtimeSeconds, target)
    }

    /**
     * Writes a regular file entry of exactly [size] bytes taken from [source].
     *
     * If the source ends early (the file shrank while being read) the rest is zero filled so the
     * archive stays well formed; extra bytes beyond [size] are ignored.
     * @return the number of bytes actually taken from [source].
     */
    fun putFile(name: String, size: Long, mode: Int, mtimeSeconds: Long, source: InputStream): Long {
        require(size >= 0) { "negative size" }
        val clean = normalize(name)
        require(clean.isNotEmpty()) { "file entry needs a name" }
        writeHeader(clean, TarFormat.TYPE_FILE, size, mode, mtimeSeconds, "")
        var left = size
        var taken = 0L
        while (left > 0) {
            val want = minOf(left, copyBuffer.size.toLong()).toInt()
            val n = source.read(copyBuffer, 0, want)
            if (n < 0) break
            out.write(copyBuffer, 0, n)
            left -= n
            taken += n
        }
        if (left > 0) {
            shrunkFiles++
            while (left > 0) {
                val n = minOf(left, zeroBlock.size.toLong()).toInt()
                out.write(zeroBlock, 0, n)
                left -= n
            }
        }
        writePadding(size)
        return taken
    }

    fun finish() {
        check(!finished) { "archive already finished" }
        finished = true
        out.write(zeroBlock)
        out.write(zeroBlock)
        out.flush()
    }

    override fun close() {
        try {
            if (!finished) finish()
        } finally {
            out.close()
        }
    }

    private fun writeHeader(name: String, type: Byte, size: Long, mode: Int, mtimeSeconds: Long, link: String) {
        check(!finished) { "archive already finished" }
        val mtime = mtimeSeconds.coerceIn(0, TarFormat.MAX_OCTAL_SIZE)
        val pax = LinkedHashMap<String, String>()
        if (!TarFormat.fitsPlainField(name, TarFormat.NAME_LEN)) pax["path"] = name
        if (!TarFormat.fitsPlainField(link, TarFormat.LINK_LEN)) pax["linkpath"] = link
        if (size > TarFormat.MAX_OCTAL_SIZE) pax["size"] = size.toString()
        if (pax.isNotEmpty()) {
            val payload = encodePax(pax)
            val paxName = "PaxHeaders/" + asciiFallback(name, TarFormat.NAME_LEN - "PaxHeaders/".length)
            out.write(buildHeader(paxName, TarFormat.TYPE_PAX, payload.size.toLong(), 0x1A4, mtime, ""))
            out.write(payload)
            writePadding(payload.size.toLong())
        }
        out.write(
            buildHeader(
                asciiFallback(name, TarFormat.NAME_LEN),
                type,
                size,
                mode,
                mtime,
                asciiFallback(link, TarFormat.LINK_LEN),
            )
        )
    }

    private fun buildHeader(name: String, type: Byte, size: Long, mode: Int, mtime: Long, link: String): ByteArray {
        val h = ByteArray(TarFormat.BLOCK)
        putAscii(h, TarFormat.NAME_OFF, TarFormat.NAME_LEN, name)
        TarFormat.writeOctal(h, TarFormat.MODE_OFF, 8, (mode and 0xFFF).toLong())
        TarFormat.writeOctal(h, TarFormat.UID_OFF, 8, 0)
        TarFormat.writeOctal(h, TarFormat.GID_OFF, 8, 0)
        if (size <= TarFormat.MAX_OCTAL_SIZE) {
            TarFormat.writeOctal(h, TarFormat.SIZE_OFF, TarFormat.SIZE_LEN, size)
        } else {
            // GNU base-256 encoding, understood by every modern tar implementation.
            h[TarFormat.SIZE_OFF] = 0x80.toByte()
            var v = size
            for (i in TarFormat.SIZE_LEN - 1 downTo 1) {
                h[TarFormat.SIZE_OFF + i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
        }
        TarFormat.writeOctal(h, TarFormat.MTIME_OFF, TarFormat.MTIME_LEN, mtime)
        h[TarFormat.TYPE_OFF] = type
        putAscii(h, TarFormat.LINK_OFF, TarFormat.LINK_LEN, link)
        putAscii(h, TarFormat.MAGIC_OFF, 6, "ustar\u0000")
        putAscii(h, TarFormat.VERSION_OFF, 2, "00")
        putAscii(h, TarFormat.UNAME_OFF, 32, "root")
        putAscii(h, TarFormat.GNAME_OFF, 32, "root")
        val sum = TarFormat.computeChecksum(h)
        val chk = java.lang.Long.toOctalString(sum).padStart(6, '0')
        putAscii(h, TarFormat.CHKSUM_OFF, 6, chk)
        h[TarFormat.CHKSUM_OFF + 6] = 0
        h[TarFormat.CHKSUM_OFF + 7] = ' '.code.toByte()
        return h
    }

    private fun writePadding(size: Long) {
        val pad = TarFormat.paddingFor(size)
        if (pad > 0) out.write(zeroBlock, 0, pad)
    }

    private fun putAscii(buf: ByteArray, off: Int, len: Int, s: String) {
        val bytes = s.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(bytes, 0, buf, off, minOf(len, bytes.size))
    }

    companion object {
        internal fun normalize(name: String): String {
            var s = name.replace('\\', '/')
            while (s.startsWith("./")) s = s.substring(2)
            s = s.trimStart('/')
            s = s.trimEnd('/')
            return s
        }

        /** Printable ASCII approximation of [s] that fits [max] bytes (used when pax carries the real value). */
        private fun asciiFallback(s: String, max: Int): String {
            val sb = StringBuilder()
            for (ch in s) sb.append(if (ch.code in 0x20..0x7E) ch else '_')
            val ascii = sb.toString()
            return if (ascii.length <= max) ascii else ascii.substring(ascii.length - max)
        }

        internal fun encodePax(records: Map<String, String>): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            for ((key, value) in records) {
                val body = " $key=$value\n".toByteArray(StandardCharsets.UTF_8)
                var len = body.size + 1
                while (true) {
                    val total = body.size + len.toString().length
                    if (total == len) break
                    len = total
                }
                result.write(len.toString().toByteArray(StandardCharsets.US_ASCII))
                result.write(body)
            }
            return result.toByteArray()
        }
    }
}
