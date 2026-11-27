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

/**
 * Conversation members for an MMS when the original thread could not be read (Android 17 hides
 * some threads from apps that are not the default SMS app). [addresses] are (type, address) pairs
 * from the MMS "addr" table; types follow PduHeaders (FROM 137, TO 151, CC 130, BCC 129).
 */
fun mmsFallbackRecipients(messageBox: Int, addresses: List<Pair<Int, String>>): Set<String> {
    val valid = addresses.filter { it.second.isNotBlank() && it.second != "insert-address-token" }
    fun of(vararg types: Int) = valid.filter { it.first in types }.map { it.second }.toCollection(LinkedHashSet())
    val result = if (messageBox == 1) {
        // Incoming: the sender, plus the other recipients of a group message. A single TO
        // address is our own number, which must not become part of the conversation.
        val others = of(151, 130)
        of(137).apply { if (others.size > 1) addAll(others) }
    } else {
        of(151, 130, 129)
    }
    return result.ifEmpty { valid.map { it.second }.toCollection(LinkedHashSet()) }
}
