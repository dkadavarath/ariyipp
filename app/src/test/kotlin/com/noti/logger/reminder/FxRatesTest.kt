package com.noti.logger.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FxRatesTest {

    @Test fun `free-text currencies map to iso codes`() {
        assertEquals("INR", FxRates.isoOf("Rs", "AED"))
        assertEquals("INR", FxRates.isoOf("Rs.", "AED"))
        assertEquals("INR", FxRates.isoOf("₹", "AED"))
        assertEquals("AED", FxRates.isoOf("Dhs", "USD"))
        assertEquals("USD", FxRates.isoOf("$", "AED"))
        assertEquals("SAR", FxRates.isoOf("sar", "AED"))
        assertNull(FxRates.isoOf("points", "AED"))
    }

    @Test fun `a bill with no currency is in the default one`() {
        assertEquals("AED", FxRates.isoOf("", "AED"))
        assertEquals("EUR", FxRates.isoOf("  ", "EUR"))
    }

    @Test fun `converts at units per home unit`() {
        val rates = mapOf("AED" to 1.0, "INR" to 22.7)
        assertEquals(810.69, FxRates.toHome(18402.75, "INR", "AED", rates)!!, 0.01)
        assertEquals(412.0, FxRates.toHome(412.0, "AED", "AED", emptyMap())!!, 0.0) // home needs no rate
        assertEquals(50.0, FxRates.toHome(50.0, "EUR", "EUR", emptyMap())!!, 0.0)
        assertNull(FxRates.toHome(10.0, "USD", "AED", rates))
    }

    @Test fun `parses the api response and rejects errors`() {
        val ok = """{"result":"success","base_code":"AED","time_last_update_unix":1,"rates":{"AED":1,"INR":22.71,"USD":0.2723}}"""
        assertEquals(22.71, FxRates.parse(ok)!!["INR"]!!, 0.0)
        assertNull(FxRates.parse("""{"result":"error","error-type":"unsupported-code"}"""))
        assertNull(FxRates.parse("not json"))
        assertNull(FxRates.parse(""))
    }

    @Test fun `rates cached for another currency are not used`() {
        val aedBased = """{"result":"success","base_code":"AED","rates":{"AED":1,"INR":22.71}}"""
        assertEquals(22.71, FxRates.parse(aedBased, "AED")!!["INR"]!!, 0.0)
        assertNull(FxRates.parse(aedBased, "USD"))
    }

    @Test fun `typed currencies normalise to iso codes`() {
        assertEquals("AED", FxRates.normalize("aed"))
        assertEquals("INR", FxRates.normalize(" Rs "))
        assertEquals("INR", FxRates.normalize("₹"))
        assertEquals("EUR", FxRates.normalize("€"))
        assertNull(FxRates.normalize(""))       // blank = automatic, not a choice
        assertNull(FxRates.normalize("XYZ"))    // three letters, but no such currency
        assertNull(FxRates.normalize("points"))
    }

    @Test fun `a country names its currency`() {
        assertEquals("AED", FxRates.currencyOfCountry("ae"))
        assertEquals("INR", FxRates.currencyOfCountry("IN"))
        assertEquals("GBP", FxRates.currencyOfCountry("gb"))
        assertNull(FxRates.currencyOfCountry(""))
        assertNull(FxRates.currencyOfCountry("ZZZ"))
    }
}
