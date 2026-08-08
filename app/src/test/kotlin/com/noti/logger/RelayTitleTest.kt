package com.noti.logger

import com.noti.logger.push.RelayTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayTitleTest {

    @Test
    fun `splits sender and sim`() {
        assertEquals("+971500000000" to "e&", RelayTitle.parse("+971500000000 on e&"))
    }

    @Test
    fun `no sim when there is no on-suffix`() {
        assertEquals("Bank" to "", RelayTitle.parse("Bank"))
    }

    @Test
    fun `splits on the last on`() {
        assertEquals("A on B" to "e&", RelayTitle.parse("A on B on e&"))
    }

    @Test
    fun `empty title`() {
        assertEquals("" to "", RelayTitle.parse(""))
    }
}
