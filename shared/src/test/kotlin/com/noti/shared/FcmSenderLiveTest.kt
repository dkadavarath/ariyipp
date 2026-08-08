package com.noti.shared

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/**
 * Verifies the on-device OAuth + FCM send code path against LIVE FCM, using the real service-account
 * key in FCM/ (gitignored). Auto-skips when the key isn't present, so CI without secrets stays green
 * — same pattern as LiveWebhookTest. Uses validateOnly so nothing is actually delivered.
 */
class FcmSenderLiveTest {

    /** The service-account key sits at repo-root FCM/; tests run with cwd = the module dir. */
    private fun serviceAccountKey(): File? =
        listOf(File("FCM"), File("../FCM"))
            .firstOrNull { it.isDirectory }
            ?.listFiles { f -> f.extension == "json" }
            ?.firstOrNull { it.readText().contains("\"private_key\"") }

    @Test
    fun validateOnly_send_reaches_fcm_and_authenticates() {
        val key = serviceAccountKey()
        assumeNotNull("no service-account key in FCM/ — skipping live test", key)

        val sender = FcmSender(key!!.readText())
        val result = sender.send("INVALID_TEST_TOKEN", mapOf("payload" to "test"), validateOnly = true)

        // A token-level rejection (not a 401/403) proves auth + project + FCM API all work; the only
        // thing wrong was our deliberately fake device token.
        assertTrue(
            "expected a token-level rejection, got HTTP ${result.httpCode}: ${result.detail}",
            result.httpCode == 400 && result.detail.contains("INVALID_ARGUMENT")
        )
    }
}
