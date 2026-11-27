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
