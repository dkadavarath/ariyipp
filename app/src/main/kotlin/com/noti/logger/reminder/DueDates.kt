package com.noti.logger.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/**
 * How a bill's due date (and a rule's anchor date) is stored: as a plain calendar date, encoded as
 * the epoch millis of that date's midnight *in UTC*. The column stays a Long, so ordering and the
 * "0 = none" convention carry on unchanged, but no time zone is involved in reading it back.
 *
 * Before database v14 these were the device's *local* midnight. Read back after a move from India
 * to the UAE, India's midnight is 22:30 the evening before, and every due date slipped a day.
 * [fromLegacy] converts those old values once (the v13 → v14 migration, and restoring an older
 * backup).
 */
object DueDates {

    const val DAY_MS = 86_400_000L

    /** The stored value for [date]. */
    fun toMs(date: LocalDate): Long = date.toEpochDay() * DAY_MS

    /** The date a stored value stands for. Callers check for 0 ("no date") first. */
    fun fromMs(ms: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(ms, DAY_MS))

    /**
     * The date an old local-midnight value meant. [zones] are where the bill most likely came from
     * (its currency's country): if they all agree on the calendar date at that instant, that's the
     * date. Otherwise - a currency spanning several zones, or none known - the instant is rounded to
     * the nearest midnight in [fallback] (the phone's zone now), which is right for any bill created
     * less than 12 hours of offset away. Pure.
     */
    fun fromLegacy(ms: Long, zones: List<ZoneId>, fallback: ZoneId): LocalDate {
        val instant = Instant.ofEpochMilli(ms)
        val dates = zones.map { instant.atZone(it).toLocalDate() }.toSet()
        if (dates.size == 1) return dates.first()
        val local = instant.atZone(fallback).toLocalDateTime()
        return if (local.hour >= 12) local.toLocalDate().plusDays(1) else local.toLocalDate()
    }

    /** [fromLegacy] as a stored value; 0 stays 0. */
    fun legacyToMs(ms: Long, zones: List<ZoneId>, fallback: ZoneId): Long =
        if (ms <= 0L) ms else toMs(fromLegacy(ms, zones, fallback))

    /**
     * The time zones of every country that uses [iso] ("INR" → Asia/Kolkata, "AED" → Asia/Dubai,
     * "EUR" → a dozen). Empty when unknown. Uses the platform's ICU zone table, so Android only.
     */
    fun zonesForCurrency(iso: String?): List<ZoneId> {
        if (iso.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<ZoneId>()
        for (cc in Locale.getISOCountries()) {
            val code = try { Currency.getInstance(Locale("", cc))?.currencyCode } catch (e: Exception) { null }
            if (code != iso) continue
            val ids = try { android.icu.util.TimeZone.getAvailableIDs(cc) } catch (e: Throwable) { emptyArray() }
            ids.forEach { id -> try { out.add(ZoneId.of(id)) } catch (e: Exception) { } }
        }
        return out.toList()
    }
}
