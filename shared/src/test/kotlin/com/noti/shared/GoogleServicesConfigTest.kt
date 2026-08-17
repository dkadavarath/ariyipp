package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleServicesConfigTest {

    private val sample = """
        {
          "project_info": {
            "project_number": "944098674829",
            "project_id": "ariyipp-demo",
            "storage_bucket": "ariyipp-demo.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:944098674829:android:abcdef123456",
                "android_client_info": { "package_name": "com.noti.logger" }
              },
              "oauth_client": [],
              "api_key": [ { "current_key": "AIzaSyDemoKeyDemoKeyDemoKeyDemoKeyDemoKey" } ],
              "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    @Test fun `looks like google-services detects the shape`() {
        assertTrue(GoogleServices.looksLikeGoogleServices(sample))
    }

    @Test fun `a service-account key does not look like google-services`() {
        val sa = """{"type":"service_account","private_key":"x","client_email":"a@b.iam.gserviceaccount.com"}"""
        assertFalse(GoogleServices.looksLikeGoogleServices(sa))
    }

    @Test fun `garbage does not look like google-services`() {
        assertFalse(GoogleServices.looksLikeGoogleServices("not json"))
    }

    @Test fun `parses the matching client by package name`() {
        val cfg = GoogleServices.parse(sample, "com.noti.logger")
        assertEquals("AIzaSyDemoKeyDemoKeyDemoKeyDemoKeyDemoKey", cfg.apiKey)
        assertEquals("1:944098674829:android:abcdef123456", cfg.applicationId)
        assertEquals("ariyipp-demo", cfg.projectId)
        assertEquals("944098674829", cfg.gcmSenderId)
    }

    @Test fun `falls back to the first client when the package doesn't match`() {
        val cfg = GoogleServices.parse(sample, "com.other.app")
        assertEquals("ariyipp-demo", cfg.projectId)
    }

    @Test(expected = Exception::class)
    fun `throws on malformed json`() {
        GoogleServices.parse("not json", "com.noti.logger")
    }

    @Test(expected = Exception::class)
    fun `throws when there is no client array`() {
        GoogleServices.parse("""{"project_info":{"project_id":"x","project_number":"1"}}""", "com.noti.logger")
    }
}
