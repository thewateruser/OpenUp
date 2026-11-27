package org.openup.data.special

/** The parts of an MMS that identify its content (used to detect duplicates on restore). */
data class MmsPartKey(val seq: Int, val ct: String?, val text: String?, val cl: String?, val name: String?)

/** Content fingerprint of an MMS, independent of the order in which parts are returned. */
fun mmsContentKey(parts: List<MmsPartKey>): String {
    val joined = parts.sortedWith(compareBy<MmsPartKey> { it.seq }.thenBy { it.ct.orEmpty() }.thenBy { it.cl.orEmpty() }.thenBy { it.text.orEmpty() })
        .joinToString("\u0002") { listOf(it.seq.toString(), it.ct, it.text, it.cl, it.name).joinToString("\u0001") { v -> v.orEmpty() } }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
