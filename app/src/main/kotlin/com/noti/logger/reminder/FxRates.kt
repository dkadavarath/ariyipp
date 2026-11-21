package com.noti.logger.reminder

import android.content.Context
import android.telephony.TelephonyManager
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Currency
import java.util.Locale

/**
 * Exchange rates for the Reminders total, which is shown in the user's default currency ([home]).
 * Fetched at most once a day from open.er-api.com (free, no key - the request names only that
 * currency, nothing about the user or their bills) and cached in Settings; when offline the last
 * cached rates are used, however old - as long as they were fetched for the same currency.
 *
 * Rates are stored as the API gives them: units of each currency per 1 unit of the home currency.
 */
object FxRates {

    /** Last resort when neither the user nor the phone's region names a currency. */
    const val FALLBACK = "USD"
    private const val URL_LATEST = "https://open.er-api.com/v6/latest/"
    private const val DAY_MS = 86_400_000L
    /** After a failed fetch, wait this long before trying again rather than on every screen open. */
    private const val RETRY_AFTER_FAILURE_MS = 60 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** The currency totals are shown in: the user's choice, else the phone's region. */
    fun home(context: Context): String =
        normalize(Settings.get(context).defaultCurrency) ?: regional(context)

    /**
     * The phone's own currency, from where it is rather than what language it speaks: the SIM's
     * country, then the network's, then the locale's region. An English UI on a UAE SIM is a
     * dirham phone; the locale alone ("en_US") would have said dollars.
     */
    fun regional(context: Context): String {
        val tm = try { context.getSystemService(TelephonyManager::class.java) } catch (e: Exception) { null }
        val countries = listOfNotNull(
            try { tm?.simCountryIso } catch (e: Exception) { null },
            try { tm?.networkCountryIso } catch (e: Exception) { null },
            Locale.getDefault().country,
        )
        return countries.firstNotNullOfOrNull { currencyOfCountry(it) } ?: FALLBACK
    }

    /** ISO currency of a two-letter country code ("ae" → AED), or null. */
    fun currencyOfCountry(country: String): String? {
        val cc = country.trim().uppercase(Locale.ROOT)
        if (cc.length != 2) return null
        return try { Currency.getInstance(Locale("", cc))?.currencyCode } catch (e: Exception) { null }
    }

    /** A user-typed currency ("aed", "Rs", "₹") as an ISO code, or null if blank or unknown -
     *  the shape [isoOf] accepts, but blank means "not set" here rather than "home". */
    fun normalize(input: String): String? {
        if (input.isBlank()) return null
        val iso = isoOf(input, FALLBACK) ?: return null
        return try { Currency.getInstance(iso).currencyCode } catch (e: Exception) { null }
    }

    /**
     * A bill's free-text currency ("Rs", "Rs.", "₹", "Dhs", "$") as an ISO code, or null when it
     * can't be told. Blank is the [home] currency: a bill entered without one is in the user's own.
     */
    fun isoOf(currency: String, home: String): String? {
        val c = currency.trim().trimEnd('.').uppercase()
        return when {
            c.isEmpty() -> home
            c == "RS" || c == "₹" || c == "INR" || c == "RUPEES" -> "INR"
            c == "AED" || c == "DHS" || c == "DH" || c == "د.إ" -> "AED"
            c == "$" || c == "US$" || c == "USD" -> "USD"
            c == "€" || c == "EUR" -> "EUR"
            c == "£" || c == "GBP" -> "GBP"
            c.length == 3 && c.all { it in 'A'..'Z' } -> c
            else -> null
        }
    }

    /** [amount] in [iso] → [home], or null when there is no rate for it. */
    fun toHome(amount: Double, iso: String, home: String, rates: Map<String, Double>): Double? {
        if (iso == home) return amount
        val perHome = rates[iso]?.takeIf { it > 0 } ?: return null
        return amount / perHome
    }

    /** Parses the API's `{"result":"success","base_code":"AED","rates":{"INR":22.7,...}}`; null for
     *  anything else, including rates for a base other than [base] (the default currency changed
     *  since they were cached - dividing by them would be silently wrong). */
    fun parse(body: String, base: String? = null): Map<String, Double>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["result"]?.jsonPrimitive?.content != "success") null
        else if (base != null && !root["base_code"]?.jsonPrimitive?.content.equals(base, ignoreCase = true)) null
        else root["rates"]?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }?.toMap()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    data class Snapshot(val rates: Map<String, Double>, val fetchedAtMs: Long)

    /** The cached rates for [home], refreshed first if they are a day old or were fetched for
     *  another currency. Blocking - call off the main thread. */
    fun current(context: Context, home: String = home(context)): Snapshot? {
        val s = Settings.get(context)
        val now = System.currentTimeMillis()
        val cached = parse(s.fxRatesJson, home)?.let { Snapshot(it, s.fxFetchedAtMs) }
        val fresh = cached != null && now - s.fxFetchedAtMs in 0 until DAY_MS
        // Changing the default currency clears the back-off (see [onHomeChanged]), so a new one is
        // fetched straight away rather than after the hour.
        val mayTry = now - s.fxAttemptAtMs !in 0 until RETRY_AFTER_FAILURE_MS
        if (fresh || !mayTry) return cached
        s.fxAttemptAtMs = now
        val body = fetch(home) ?: return cached
        val rates = parse(body, home) ?: return cached
        s.fxRatesJson = body
        s.fxFetchedAtMs = now
        return Snapshot(rates, now)
    }

    /** The default currency changed: allow an immediate fetch for it. */
    fun onHomeChanged(context: Context) {
        Settings.get(context).fxAttemptAtMs = 0L
    }

    private fun fetch(home: String): String? {
        val conn = URL(URL_LATEST + home).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Diag.log("exchange rates: fetch failed (${e.javaClass.simpleName}) - using cached")
            null
        } finally {
            conn.disconnect()
        }
    }
}
