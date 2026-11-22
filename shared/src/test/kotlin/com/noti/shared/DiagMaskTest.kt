package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagMaskTest {
    @Test fun `a phone number keeps only its last four digits`() {
        assertEquals("…4321", Diag.mask("+91 98765 54321"))
        assertEquals("…4321", Diag.mask("9876554321"))
    }

    @Test fun `named senders and short codes stay readable`() {
        assertEquals("VM-HDFCBK", Diag.mask("VM-HDFCBK"))
        assertEquals("5676", Diag.mask("5676"))
    }
}
