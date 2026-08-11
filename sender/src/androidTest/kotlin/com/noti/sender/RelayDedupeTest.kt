package com.noti.sender

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayDedupeTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before @After
    fun clear() {
        ctx.getSharedPreferences("noti_relay_dedupe", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun record_thenAlreadySent_isTrue() {
        assertFalse(RelayDedupe.alreadySent(ctx, "n8n", "abc"))
        RelayDedupe.record(ctx, "n8n", "abc")
        assertTrue(RelayDedupe.alreadySent(ctx, "n8n", "abc"))
    }

    @Test
    fun legsAreIndependent() {
        RelayDedupe.record(ctx, "fcm", "k1")
        assertTrue(RelayDedupe.alreadySent(ctx, "fcm", "k1"))
        assertFalse(RelayDedupe.alreadySent(ctx, "n8n", "k1")) // same key, other leg still open
    }

    @Test
    fun unknownKey_isNotSent() {
        RelayDedupe.record(ctx, "n8n", "known")
        assertFalse(RelayDedupe.alreadySent(ctx, "n8n", "other"))
    }
}
