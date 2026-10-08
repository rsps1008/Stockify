package com.rsps1008.stockify.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

@Serializable
internal data class TaiwanHolidayItem(
    val date: String,
    val week: String,
    val isHoliday: Boolean,
    val description: String
)

@Serializable
internal data class TaiwanHolidayCalendarAsset(
    val calendarVersion: Int,
    val source: String,
    val items: List<TaiwanHolidayItem>
)

@Serializable
internal data class TaiwanHolidayCalendarSnapshot(
    val year: Int,
    val calendarVersion: Int,
    val updatedAtEpochMillis: Long,
    val holidays: Map<String, Boolean>,
    val source: String = "unknown"
) {
    fun toDateMap(): Map<LocalDate, Boolean>? {
        val parsed = holidays.mapNotNull { (date, isHoliday) ->
            runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to isHoliday }
        }.toMap()
        return parsed.takeIf { parsed.size == holidays.size && isCompleteTaiwanHolidayCalendar(year, it) }
    }
}

internal fun parseTaiwanHolidayItems(year: Int, items: List<TaiwanHolidayItem>): Map<LocalDate, Boolean> {
    require(items.size == LocalDate.of(year, 1, 1).lengthOfYear()) { "Taiwan calendar does not cover the full year" }
    val parsed = items.map { item ->
        val date = LocalDate.parse(item.date, DateTimeFormatter.BASIC_ISO_DATE)
        require(date.year == year) { "Taiwan calendar contains another year" }
        require(item.week == WEEKDAY_LABELS[date.dayOfWeek.value % 7]) { "Taiwan calendar weekday does not match $date" }
        date to item.isHoliday
    }
    val calendar = parsed.toMap()
    require(calendar.size == items.size) { "Taiwan calendar contains duplicate dates" }
    require(isCompleteTaiwanHolidayCalendar(year, calendar)) { "Taiwan calendar has missing dates" }
    return calendar
}

internal fun isCompleteTaiwanHolidayCalendar(year: Int, calendar: Map<LocalDate, Boolean>): Boolean {
    val firstDate = LocalDate.of(year, 1, 1)
    if (calendar.size != firstDate.lengthOfYear() || calendar.keys.any { it.year != year }) return false
    return (0 until firstDate.lengthOfYear()).all { firstDate.plus(it.toLong(), ChronoUnit.DAYS) in calendar }
}

private val WEEKDAY_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")

/** Keep each year's calendar for the process lifetime; retry only until that year is available. */
internal class TaiwanHolidayCache(
    private val loadYear: suspend (Int) -> TaiwanHolidayCalendarSnapshot,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readSavedYear: suspend (Int) -> TaiwanHolidayCalendarSnapshot? = { null },
    private val saveYear: suspend (TaiwanHolidayCalendarSnapshot) -> Unit = {},
    private val loadBundledYear: suspend (Int) -> TaiwanHolidayCalendarSnapshot? = { null }
) {
    private val mutex = Mutex()
    private data class YearCalendar(
        val snapshot: TaiwanHolidayCalendarSnapshot,
        val holidays: Map<LocalDate, Boolean>
    )

    private val calendars = mutableMapOf<Int, YearCalendar>()
    private val retryAfterByYear = mutableMapOf<Int, Long>()

    suspend fun isHoliday(date: LocalDate): Boolean = mutex.withLock {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            return@withLock true
        }
        calendars[date.year]?.let { return@withLock it.holidays[date] ?: false }

        val now = nowMillis()
        if (now < (retryAfterByYear[date.year] ?: 0L)) return@withLock false

        val saved = try {
            readSavedYear(date.year)?.takeIf { it.year == date.year && it.toDateMap() != null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        var bundledInvalid = false
        val bundled = try {
            loadBundledYear(date.year)?.takeIf { it.year == date.year && it.toDateMap() != null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            bundledInvalid = true
            null
        }

        val newerBundle = bundled?.takeIf { saved == null || it.calendarVersion > saved.calendarVersion }
        if (newerBundle != null) {
            return@withLock useCalendar(date.year, newerBundle, date, now)
        }
        if (saved != null && !bundledInvalid) {
            calendars[date.year] = YearCalendar(saved, requireNotNull(saved.toDateMap()))
            return@withLock calendars.getValue(date.year).holidays[date] ?: false
        }
        if (bundled != null) {
            return@withLock useCalendar(date.year, bundled, date, now)
        }

        try {
            val downloaded = loadYear(date.year)
            require(downloaded.year == date.year && downloaded.toDateMap() != null) {
                "Calendar is incomplete or contains another year"
            }
            useCalendar(date.year, downloaded, date, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (saved != null) {
                calendars[date.year] = YearCalendar(saved, requireNotNull(saved.toDateMap()))
                calendars.getValue(date.year).holidays[date] ?: false
            } else {
                retryAfterByYear[date.year] = now + 300_000L
                false
            }
        }
    }

    private suspend fun useCalendar(
        year: Int,
        snapshot: TaiwanHolidayCalendarSnapshot,
        date: LocalDate,
        now: Long
    ): Boolean {
        val holidays = requireNotNull(snapshot.toDateMap())
        val persisted = snapshot.copy(updatedAtEpochMillis = now)
        calendars[year] = YearCalendar(persisted, holidays)
        retryAfterByYear.remove(year)
        try {
            saveYear(persisted)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The validated calendar remains available in memory.
        }
        return holidays[date] ?: false
    }
}
