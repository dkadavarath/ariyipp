package com.noti.logger.work

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the constraint-decision logic in [UploadScheduler].
 *
 * [NetworkType] is a plain Kotlin enum with no Android-framework dependency, so it
 * is safe to reference in JVM unit tests.  [androidx.work.Constraints] is Parcelable-
 * based and cannot be instantiated on JVM, which is why [UploadScheduler.constraintDecisions]
 * exposes the mapping logic separately — it is called by [buildConstraints] so the
 * production path is exercised end-to-end, and tested here without Robolectric overhead.
 */
class UploadSchedulerTest {

    // ---- Network-type mapping ----

    @Test
    fun `requireUnmetered true maps to UNMETERED network type`() {
        val (networkType, _) = UploadScheduler.constraintDecisions(
            requireUnmetered = true,
            requireCharging = false
        )
        assertEquals(NetworkType.UNMETERED, networkType)
    }

    @Test
    fun `requireUnmetered false maps to CONNECTED network type`() {
        val (networkType, _) = UploadScheduler.constraintDecisions(
            requireUnmetered = false,
            requireCharging = false
        )
        assertEquals(NetworkType.CONNECTED, networkType)
    }

    // ---- Charging-constraint mapping ----

    @Test
    fun `requireCharging true is preserved`() {
        val (_, charging) = UploadScheduler.constraintDecisions(
            requireUnmetered = false,
            requireCharging = true
        )
        assertTrue(charging)
    }

    @Test
    fun `requireCharging false is preserved`() {
        val (_, charging) = UploadScheduler.constraintDecisions(
            requireUnmetered = false,
            requireCharging = false
        )
        assertFalse(charging)
    }

    // ---- Combined variants ----

    @Test
    fun `requireUnmetered true and requireCharging true both set`() {
        val (networkType, charging) = UploadScheduler.constraintDecisions(
            requireUnmetered = true,
            requireCharging = true
        )
        assertEquals(NetworkType.UNMETERED, networkType)
        assertTrue(charging)
    }

    @Test
    fun `requireUnmetered false and requireCharging false both set`() {
        val (networkType, charging) = UploadScheduler.constraintDecisions(
            requireUnmetered = false,
            requireCharging = false
        )
        assertEquals(NetworkType.CONNECTED, networkType)
        assertFalse(charging)
    }
}
