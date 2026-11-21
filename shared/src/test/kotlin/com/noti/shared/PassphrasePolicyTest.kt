package com.noti.shared

import com.noti.shared.PassphrasePolicy.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassphrasePolicyTest {
    @Test fun `good passphrases pass`() {
        assertNull(PassphrasePolicy.problem("river-lamp-orbit-sugar"))
        assertNull(PassphrasePolicy.problem("Tr0ub4dor&3-horse!"))
        assertNull(PassphrasePolicy.problem("my cat sleeps on keys"))
    }

    @Test fun `too short`() = assertEquals(Problem.TOO_SHORT, PassphrasePolicy.problem("river-lamp-orb"))

    @Test fun `the reported example is refused`() {
        assertEquals(Problem.TOO_SHORT, PassphrasePolicy.problem("111111111111"))
        assertEquals(Problem.TOO_FEW_DISTINCT, PassphrasePolicy.problem("1111111111111111"))
    }

    @Test fun `few distinct characters`() = assertEquals(Problem.TOO_FEW_DISTINCT, PassphrasePolicy.problem("abababababababab"))

    @Test fun `repeats`() {
        assertEquals(Problem.REPEATS, PassphrasePolicy.problem("abcdefabcdefabcdef"))
        assertEquals(Problem.REPEATS, PassphrasePolicy.problem("river-lamp-xxxxsugar"))
    }

    @Test fun `sequences`() {
        assertEquals(Problem.SEQUENCE, PassphrasePolicy.problem("abcdefghijklmnop"))
        assertEquals(Problem.SEQUENCE, PassphrasePolicy.problem("my-pass-1234567-x"))
        assertEquals(Problem.SEQUENCE, PassphrasePolicy.problem("QWERTYuiop!-zz9+"))
        assertEquals(Problem.SEQUENCE, PassphrasePolicy.problem("lamp-98765-river"))
    }
}
