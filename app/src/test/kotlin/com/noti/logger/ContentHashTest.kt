package com.noti.logger

import com.noti.logger.util.contentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHashTest {

    @Test
    fun `identical content yields identical hash`() {
        val a = contentHash("com.app", "Title", "Body", "Big", "Sub")
        val b = contentHash("com.app", "Title", "Body", "Big", "Sub")
        assertEquals(a, b)
    }

    @Test
    fun `different text yields different hash`() {
        val a = contentHash("com.app", "Title", "Body", null, null)
        val b = contentHash("com.app", "Title", "Body2", null, null)
        assertNotEquals(a, b)
    }

    @Test
    fun `different package yields different hash`() {
        val a = contentHash("com.app.one", "Title", "Body", null, null)
        val b = contentHash("com.app.two", "Title", "Body", null, null)
        assertNotEquals(a, b)
    }

    @Test
    fun `null and empty are treated the same`() {
        val a = contentHash("com.app", "Title", null, null, null)
        val b = contentHash("com.app", "Title", "", "", "")
        assertEquals(a, b)
    }

    @Test
    fun `output is 64-char hex (sha-256)`() {
        val h = contentHash("com.app", "t", "x", null, null)
        assertEquals(64, h.length)
        assert(h.all { it in "0123456789abcdef" })
    }
}
