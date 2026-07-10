package com.noti.logger

import com.noti.logger.upload.alreadyExistsUids
import com.noti.logger.upload.genuineFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureClassificationTest {

    @Test
    fun `extracts uid from already-exists message`() {
        val failures = listOf("Key (uid)=(pt-123-A) already exists.")
        assertEquals(setOf("pt-123-A"), failures.alreadyExistsUids())
    }

    @Test
    fun `handles multiple already-exists messages`() {
        val failures = listOf(
            "Key (uid)=(a) already exists.",
            "Key (uid)=(b) already exists."
        )
        assertEquals(setOf("a", "b"), failures.alreadyExistsUids())
    }

    @Test
    fun `already-exists match is case-insensitive`() {
        val failures = listOf("Key (uid)=(x) ALREADY EXISTS")
        assertEquals(setOf("x"), failures.alreadyExistsUids())
    }

    @Test
    fun `genuine failure is not treated as already-exists`() {
        val failures = listOf(
            "Key (uid)=(dup) already exists.",
            "null value in column \"post_time\" violates not-null constraint"
        )
        assertEquals(setOf("dup"), failures.alreadyExistsUids())
        assertEquals(
            listOf("null value in column \"post_time\" violates not-null constraint"),
            failures.genuineFailures()
        )
    }

    @Test
    fun `no already-exists uids when none match`() {
        val failures = listOf("some other error", "timeout")
        assertTrue(failures.alreadyExistsUids().isEmpty())
        assertEquals(failures, failures.genuineFailures())
    }

    @Test
    fun `already-exists message without parseable uid yields no uid`() {
        val failures = listOf("record already exists")
        assertTrue(failures.alreadyExistsUids().isEmpty())
        // Still classified as not-genuine (it IS an already-exists message).
        assertTrue(failures.genuineFailures().isEmpty())
    }
}
