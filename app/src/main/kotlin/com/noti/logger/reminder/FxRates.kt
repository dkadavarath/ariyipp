package com.noti.logger.reminder

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exchange rates for the Reminders total, which is shown in AED. Fetched at most once a day from
 * open.er-api.com (free, no key - the request carries nothing about the user or their bills) and
 * cached in Settings; when offline the last cached rates are used, however old.
 *
 * Rates are stored as the API gives them: units of each currency per 1 AED.
 */
object FxRates {

    const val HOME = "AED"
    private const val URL_LATEST = "https://open.er-api.com/v6/latest/AED"
    private const val DAY_MS = 86_400_000L
    /** After a failed fetch, wait this long before trying again rather than on every screen open. */
    private const val RETRY_AFTER_FAILURE_MS = 60 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A bill's free-text currency ("Rs", "Rs.", "₹", "Dhs", "$") as an ISO code, or null when it
     * can't be told. Blank is the home currency: a bill entered without one is an AED bill here.
     */
    fun isoOf(currency: String): String? {
        val c = currency.trim().trimEnd('.').uppercase()
        return when {
            c.isEmpty() -> HOME
            c == "RS" || c == "₹" || c == "INR" || c == "RUPEES" -> "INR"
            c == "AED" || c == "DHS" || c == "DH" || c == "د.إ" -> "AED"
            c == "$" || c == "US$" || c == "USD" -> "USD"
            c == "€" || c == "EUR" -> "EUR"
            c == "£" || c == "GBP" -> "GBP"
            c.length == 3 && c.all { it in 'A'..'Z' } -> c
            else -> null
        }
    }

    /** [amount] in [iso] → AED, or null when there is no rate for it. */
    fun toHome(amount: Double, iso: String, rates: Map<String, Double>): Double? {
        if (iso == HOME) return amount
        val perAed = rates[iso]?.takeIf { it > 0 } ?: return null
        return amount / perAed
    }

    /** Parses the API's `{"result":"success","rates":{"INR":22.7,...}}`; null for anything else. */
    fun parse(body: String): Map<String, Double>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["result"]?.jsonPrimitive?.content != "success") null
        else root["rates"]?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }?.toMap()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    data class Snapshot(val rates: Map<String, Double>, val fetchedAtMs: Long)

    /** The cached rates, refreshed first if they are a day old. Blocking - call off the main thread. */
    fun current(context: Context): Snapshot? {
        val s = Settings.get(context)
        val now = System.currentTimeMillis()
        val cached = parse(s.fxRatesJson)?.let { Snapshot(it, s.fxFetchedAtMs) }
        val fresh = cached != null && now - s.fxFetchedAtMs in 0 until DAY_MS
        val mayTry = now - s.fxAttemptAtMs !in 0 until RETRY_AFTER_FAILURE_MS
        if (fresh || !mayTry) return cached
        s.fxAttemptAtMs = now
        val body = fetch() ?: return cached
        if (parse(body) == null) return cached
        s.fxRatesJson = body
        s.fxFetchedAtMs = now
        return parse(body)?.let { Snapshot(it, now) }
    }

    private fun fetch(): String? {
        val conn = URL(URL_LATEST).openConnection() as HttpURLConnection
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
