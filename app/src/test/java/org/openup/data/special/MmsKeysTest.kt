package org.openup.data.special

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MmsKeysTest {
    @Test
    fun contentKeyIgnoresPartOrderButNotContent() {
        val a = MmsPartKey(-1, "application/smil", "<smil/>", "smil.xml", null)
        val b = MmsPartKey(0, "text/plain", "hello", "text_0.txt", null)
        val c = MmsPartKey(0, "image/jpeg", null, "img.jpg", "img.jpg")
        assertEquals(mmsContentKey(listOf(a, b, c)), mmsContentKey(listOf(c, b, a)))
        assertNotEquals(mmsContentKey(listOf(a, b)), mmsContentKey(listOf(a, b.copy(text = "hello!"))))
        assertNotEquals(mmsContentKey(listOf(b)), mmsContentKey(emptyList()))
    }
}

class MmsFallbackRecipientsTest {
    private val me = "+15550000"
    private val bob = "+15551111"
    private val ann = "+15552222"

    @Test
    fun incomingOneToOneExcludesOwnNumber() {
        assertEquals(setOf(bob), mmsFallbackRecipients(1, listOf(137 to bob, 151 to me)))
    }

    @Test
    fun incomingGroupKeepsOtherMembers() {
        assertEquals(setOf(bob, me, ann), mmsFallbackRecipients(1, listOf(137 to bob, 151 to me, 151 to ann)))
    }

    @Test
    fun sentUsesRecipientsOnly() {
        assertEquals(setOf(bob, ann), mmsFallbackRecipients(2, listOf(137 to "insert-address-token", 151 to bob, 130 to ann)))
    }

    @Test
    fun fallsBackToAnyAddress() {
        assertEquals(setOf(bob), mmsFallbackRecipients(2, listOf(137 to bob)))
        assertEquals(emptySet<String>(), mmsFallbackRecipients(1, listOf(137 to "insert-address-token")))
    }
}
