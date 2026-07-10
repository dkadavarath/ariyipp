package com.noti.logger

import com.noti.logger.util.AppInfo
import com.noti.logger.util.InstalledApps
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsFilterTest {

    private val apps = listOf(
        AppInfo("com.whatsapp", "WhatsApp", null),
        AppInfo("com.google.android.gm", "Gmail", null),
        AppInfo("com.slack", "Slack", null)
    )

    @Test
    fun `blank query returns all`() {
        assertEquals(3, InstalledApps.filter(apps, "   ").size)
    }

    @Test
    fun `matches on label case-insensitively`() {
        val r = InstalledApps.filter(apps, "whats")
        assertEquals(listOf("com.whatsapp"), r.map { it.packageName })
    }

    @Test
    fun `matches on package name`() {
        val r = InstalledApps.filter(apps, "android.gm")
        assertEquals(listOf("com.google.android.gm"), r.map { it.packageName })
    }

    @Test
    fun `no match returns empty`() {
        assertEquals(0, InstalledApps.filter(apps, "zzz").size)
    }
}
