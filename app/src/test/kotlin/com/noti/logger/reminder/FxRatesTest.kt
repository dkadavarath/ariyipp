package com.noti.logger.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FxRatesTest {

    @Test fun `free-text currencies map to iso codes`() {
        assertEquals("INR", FxRates.isoOf("Rs"))
        assertEquals("INR", FxRates.isoOf("Rs."))
        assertEquals("INR", FxRates.isoOf("₹"))
        assertEquals("AED", FxRates.isoOf("Dhs"))
        assertEquals("AED", FxRates.isoOf(""))      // no currency = home
        assertEquals("USD", FxRates.isoOf("$"))
        assertEquals("SAR", FxRates.isoOf("sar"))
        assertNull(FxRates.isoOf("points"))
    }

    @Test fun `converts at units per AED`() {
        val rates = mapOf("AED" to 1.0, "INR" to 22.7)
        assertEquals(810.69, FxRates.toHome(18402.75, "INR", rates)!!, 0.01)
        assertEquals(412.0, FxRates.toHome(412.0, "AED", emptyMap())!!, 0.0) // home needs no rate
        assertNull(FxRates.toHome(10.0, "USD", rates))
    }

    @Test fun `parses the api response and rejects errors`() {
        val ok = """{"result":"success","base_code":"AED","time_last_update_unix":1,"rates":{"AED":1,"INR":22.71,"USD":0.2723}}"""
        assertEquals(22.71, FxRates.parse(ok)!!["INR"]!!, 0.0)
        assertNull(FxRates.parse("""{"result":"error","error-type":"unsupported-code"}"""))
        assertNull(FxRates.parse("not json"))
        assertNull(FxRates.parse(""))
    }
}
