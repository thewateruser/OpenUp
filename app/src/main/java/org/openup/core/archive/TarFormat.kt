package org.openup.core.archive

import java.nio.charset.StandardCharsets

/**
 * Constants and low level helpers for the POSIX ustar / pax archive format.
 *
 * OpenUp writes ustar headers, falling back to pax extended headers for anything that does not fit
 * (long or non-ASCII paths, huge files). The reader additionally understands GNU long-name records
 * and base-256 numeric fields so archives produced by other tools can be read as well.
 */
internal object TarFormat {
    const val BLOCK = 512

    const val TYPE_FILE = '0'.code.toByte()
    const val TYPE_FILE_OLD = 0.toByte()
    const val TYPE_HARDLINK = '1'.code.toByte()
    const val TYPE_SYMLINK = '2'.code.toByte()
    const val TYPE_CHAR = '3'.code.toByte()
    const val TYPE_BLOCK = '4'.code.toByte()
    const val TYPE_DIR = '5'.code.toByte()
    const val TYPE_FIFO = '6'.code.toByte()
    const val TYPE_CONTIGUOUS = '7'.code.toByte()
    const val TYPE_PAX = 'x'.code.toByte()
    const val TYPE_PAX_GLOBAL = 'g'.code.toByte()
    const val TYPE_GNU_LONGNAME = 'L'.code.toByte()
    const val TYPE_GNU_LONGLINK = 'K'.code.toByte()

    const val NAME_OFF = 0
    const val NAME_LEN = 100
    const val MODE_OFF = 100
    const val UID_OFF = 108
    const val GID_OFF = 116
    const val SIZE_OFF = 124
    const val SIZE_LEN = 12
    const val MTIME_OFF = 136
    const val MTIME_LEN = 12
    const val CHKSUM_OFF = 148
    const val CHKSUM_LEN = 8
    const val TYPE_OFF = 156
    const val LINK_OFF = 157
    const val LINK_LEN = 100
    const val MAGIC_OFF = 257
    const val VERSION_OFF = 263
    const val UNAME_OFF = 265
    const val GNAME_OFF = 297
    const val PREFIX_OFF = 345
    const val PREFIX_LEN = 155

    /** Largest value representable in an 11 digit octal field (the size field). */
    const val MAX_OCTAL_SIZE = 8_589_934_591L // 077777777777

    fun computeChecksum(header: ByteArray): Long {
        var sum = 0L
        for (i in 0 until BLOCK) {
            sum += if (i in CHKSUM_OFF until CHKSUM_OFF + CHKSUM_LEN) 0x20 else (header[i].toInt() and 0xFF)
        }
        return sum
    }

    fun computeSignedChecksum(header: ByteArray): Long {
        var sum = 0L
        for (i in 0 until BLOCK) {
            sum += if (i in CHKSUM_OFF until CHKSUM_OFF + CHKSUM_LEN) 0x20 else header[i].toInt()
        }
        return sum
    }

    fun isAllZero(block: ByteArray): Boolean {
        for (b in block) if (b.toInt() != 0) return false
        return true
    }

    /** Writes [value] as a zero padded octal number followed by a NUL into a field of [len] bytes. */
    fun writeOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
        require(value >= 0) { "negative value $value" }
        val digits = len - 1
        val s = java.lang.Long.toOctalString(value)
        require(s.length <= digits) { "value $value does not fit in $digits octal digits" }
        val padded = "0".repeat(digits - s.length) + s
        for (i in 0 until digits) buf[off + i] = padded[i].code.toByte()
        buf[off + digits] = 0
    }

    fun parseNumeric(buf: ByteArray, off: Int, len: Int): Long {
        val first = buf[off].toInt() and 0xFF
        if (first and 0x80 != 0) {
            // GNU base-256 encoding (big endian, two's complement, first byte flag bit).
            var result = (first and 0x7F).toLong()
            if (first and 0x40 != 0) throw TarException("negative base-256 number")
            for (i in 1 until len) {
                if (result > (Long.MAX_VALUE shr 8)) throw TarException("base-256 number overflow")
                result = (result shl 8) or (buf[off + i].toLong() and 0xFF)
            }
            return result
        }
        var i = off
        val end = off + len
        while (i < end && (buf[i] == ' '.code.toByte() || buf[i].toInt() == 0)) i++
        var result = 0L
        while (i < end) {
            val c = buf[i].toInt()
            if (c == 0 || c == ' '.code) break
            if (c < '0'.code || c > '7'.code) throw TarException("invalid octal digit in header")
            if (result > (Long.MAX_VALUE shr 3)) throw TarException("octal number overflow")
            result = (result shl 3) or (c - '0'.code).toLong()
            i++
        }
        return result
    }

    fun parseString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, off, end - off, StandardCharsets.UTF_8)
    }

    /** Returns true if [s] can be stored verbatim in a plain ustar field of [maxLen] bytes. */
    fun fitsPlainField(s: String, maxLen: Int): Boolean {
        if (s.length > maxLen) return false
        for (ch in s) if (ch.code < 0x20 || ch.code > 0x7E) return false
        return true
    }

    fun paddingFor(size: Long): Int {
        val rem = (size % BLOCK).toInt()
        return if (rem == 0) 0 else BLOCK - rem
    }
}

class TarException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
