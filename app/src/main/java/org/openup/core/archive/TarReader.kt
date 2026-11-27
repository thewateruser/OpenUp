package org.openup.core.archive

import java.io.Closeable
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Streaming reader for tar archives (ustar, pax and GNU long name extensions).
 *
 * Usage: call [nextEntry] until it returns null, reading file contents from [entryStream] in
 * between. Truncated or corrupted archives raise [TarException] instead of silently ending.
 */
class TarReader(private val input: InputStream) : Closeable {
    private val header = ByteArray(TarFormat.BLOCK)
    private var remaining = 0L
    private var padding = 0
    private var ended = false

    private val entryInput = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b < 0) throw TarException("Archive is truncated")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining).toInt()
            val n = input.read(b, off, want)
            if (n < 0) throw TarException("Archive is truncated")
            remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, input.available().toLong()).toInt()

        override fun close() {
            // Closing an entry stream must not close the archive.
        }
    }

    /** Returns the next entry or null at the end of the archive. */
    fun nextEntry(): TarEntry? {
        if (ended) return null
        skipFully(remaining + padding)
        remaining = 0
        padding = 0

        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var paxMtime: Long? = null
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            if (!readBlock(header)) throw TarException("Archive is truncated (missing end marker)")
            if (TarFormat.isAllZero(header)) {
                ended = true
                return null
            }
            val stored = TarFormat.parseNumeric(header, TarFormat.CHKSUM_OFF, TarFormat.CHKSUM_LEN)
            if (stored != TarFormat.computeChecksum(header) && stored != TarFormat.computeSignedChecksum(header)) {
                throw TarException("Corrupted archive (bad header checksum)")
            }
            val type = header[TarFormat.TYPE_OFF]
            val size = TarFormat.parseNumeric(header, TarFormat.SIZE_OFF, TarFormat.SIZE_LEN)
            when (type) {
                TarFormat.TYPE_PAX, TarFormat.TYPE_PAX_GLOBAL -> {
                    val payload = readPayload(size)
                    if (type == TarFormat.TYPE_PAX) {
                        val records = parsePax(payload)
                        records["path"]?.let { paxPath = it }
                        records["linkpath"]?.let { paxLink = it }
                        records["size"]?.let { paxSize = it.toLongOrNull() ?: throw TarException("Bad pax size") }
                        records["mtime"]?.let { paxMtime = it.substringBefore('.').toLongOrNull() }
                    }
                    continue
                }
                TarFormat.TYPE_GNU_LONGNAME -> {
                    gnuLongName = String(readPayload(size), StandardCharsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                TarFormat.TYPE_GNU_LONGLINK -> {
                    gnuLongLink = String(readPayload(size), StandardCharsets.UTF_8).trimEnd('\u0000')
                    continue
                }
            }

            val isUstar = TarFormat.parseString(header, TarFormat.MAGIC_OFF, 5) == "ustar"
            var name = paxPath ?: gnuLongName ?: run {
                val base = TarFormat.parseString(header, TarFormat.NAME_OFF, TarFormat.NAME_LEN)
                val prefix = if (isUstar) TarFormat.parseString(header, TarFormat.PREFIX_OFF, TarFormat.PREFIX_LEN) else ""
                if (prefix.isEmpty()) base else "$prefix/$base"
            }
            val link = paxLink ?: gnuLongLink ?: TarFormat.parseString(header, TarFormat.LINK_OFF, TarFormat.LINK_LEN)
            val dataSize = paxSize ?: size
            if (dataSize < 0) throw TarException("Negative entry size")
            val mtime = paxMtime ?: TarFormat.parseNumeric(header, TarFormat.MTIME_OFF, TarFormat.MTIME_LEN)
            val mode = TarFormat.parseNumeric(header, TarFormat.MODE_OFF, 8).toInt()

            var entryType = when (type) {
                TarFormat.TYPE_FILE, TarFormat.TYPE_FILE_OLD, TarFormat.TYPE_CONTIGUOUS -> TarEntry.Type.FILE
                TarFormat.TYPE_DIR -> TarEntry.Type.DIRECTORY
                TarFormat.TYPE_SYMLINK -> TarEntry.Type.SYMLINK
                else -> TarEntry.Type.OTHER
            }
            if (entryType == TarEntry.Type.FILE && name.endsWith("/")) entryType = TarEntry.Type.DIRECTORY
            name = TarWriter.normalize(name)

            remaining = dataSize
            padding = TarFormat.paddingFor(dataSize)
            return TarEntry(
                name = name,
                type = entryType,
                size = if (entryType == TarEntry.Type.FILE) dataSize else 0,
                mode = mode,
                mtimeSeconds = mtime,
                linkTarget = link,
            )
        }
    }

    /** Stream with the contents of the entry returned by the last [nextEntry] call. */
    fun entryStream(): InputStream = entryInput

    /**
     * Reads and discards everything after the end-of-archive marker. Call it once [nextEntry]
     * returned null so that the producer of a pipe is never cut off and integrity checks that run
     * at end of stream (e.g. a SHA-256 verifier) are triggered.
     */
    fun readToEnd() {
        check(ended) { "archive not fully read yet" }
        val scratch = ByteArray(8192)
        while (input.read(scratch) >= 0) {
            // discard padding
        }
    }

    override fun close() = input.close()

    private fun readPayload(size: Long): ByteArray {
        if (size > MAX_META_PAYLOAD) throw TarException("Extended header too large")
        val buf = ByteArray(size.toInt())
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw TarException("Archive is truncated")
            off += n
        }
        skipFully(TarFormat.paddingFor(size).toLong())
        return buf
    }

    private fun readBlock(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw TarException("Archive is truncated")
            }
            off += n
        }
        return true
    }

    private fun skipFully(count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw TarException("Archive is truncated")
            left -= n
        }
    }

    companion object {
        private const val MAX_META_PAYLOAD = 1L shl 20

        internal fun parsePax(payload: ByteArray): Map<String, String> {
            val result = HashMap<String, String>()
            var pos = 0
            while (pos < payload.size) {
                var sp = pos
                while (sp < payload.size && payload[sp] != ' '.code.toByte()) sp++
                if (sp >= payload.size) throw TarException("Malformed pax header")
                val len = String(payload, pos, sp - pos, StandardCharsets.US_ASCII).toIntOrNull()
                    ?: throw TarException("Malformed pax header")
                val end = pos + len
                if (len <= 0 || end > payload.size || end <= sp + 1 || payload[end - 1] != '\n'.code.toByte()) {
                    throw TarException("Malformed pax header")
                }
                val record = String(payload, sp + 1, end - sp - 2, StandardCharsets.UTF_8)
                val eq = record.indexOf('=')
                if (eq <= 0) throw TarException("Malformed pax header")
                result[record.substring(0, eq)] = record.substring(eq + 1)
                pos = end
            }
            return result
        }
    }
}
