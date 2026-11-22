package com.noti.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookUrlPolicyEmulatorTest {
    @Test fun `plain http to the emulator host only when allowed`() {
        val was = WebhookUrlPolicy.allowEmulatorHost
        try {
            WebhookUrlPolicy.allowEmulatorHost = false
            assertFalse(WebhookUrlPolicy.isAllowed("http://10.0.2.2:5678/hook"))
            assertTrue(WebhookUrlPolicy.isAllowed("http://127.0.0.1:5678/hook"))
            assertTrue(WebhookUrlPolicy.isAllowed("https://10.0.2.2/hook"))
            WebhookUrlPolicy.allowEmulatorHost = true
            assertTrue(WebhookUrlPolicy.isAllowed("http://10.0.2.2:5678/hook"))
        } finally {
            WebhookUrlPolicy.allowEmulatorHost = was
        }
    }
}
