package com.rsps1008.stockify

import com.rsps1008.stockify.data.TaiwanHolidayCache
import com.rsps1008.stockify.data.TaiwanHolidayCalendarSnapshot
import com.rsps1008.stockify.data.TaiwanHolidayItem
import com.rsps1008.stockify.data.parseTaiwanHolidayItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TaiwanHolidayCacheTest {
    private val date = LocalDate.of(2026, 10, 9)

    @Test
    fun calendarParserRequiresEveryDateAndCorrectWeekday() {
        val items = sourceItems(2026)
        assertEquals(365, parseTaiwanHolidayItems(2026, items).size)
        assertThrows(IllegalArgumentException::class.java) {
            parseTaiwanHolidayItems(2026, items.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTaiwanHolidayItems(2026, items.mapIndexed { index, item ->
                if (index == 0) item.copy(week = "一") else item
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTaiwanHolidayItems(2026, items.dropLast(1) + items.first())
        }
    }

    @Test
    fun repeatedAndConcurrentLookupsLoadOnlyOncePerYear() = runBlocking {
        var loads = 0
        val cache = TaiwanHolidayCache(
            loadYear = { loads++; delay(10); snapshot(2026, overrides = mapOf(date to true)) }
        )
        assertTrue((1..12).map { async { cache.isHoliday(date) } }.awaitAll().all { it })
        assertEquals(1, loads)
        assertTrue(cache.isHoliday(date))
        assertEquals(1, loads)
    }

    @Test
    fun weekendsDoNotLoadAndYearRolloverLoadsMissingYear() = runBlocking {
        val years = mutableListOf<Int>()
        val newYear = LocalDate.of(2027, 1, 1)
        val cache = TaiwanHolidayCache(loadYear = { year ->
            years += year
            snapshot(year, overrides = if (year == 2027) mapOf(newYear to true) else emptyMap())
        })
        assertTrue(cache.isHoliday(LocalDate.of(2026, 10, 10)))
        assertTrue(years.isEmpty())
        assertFalse(cache.isHoliday(date))
        assertTrue(cache.isHoliday(newYear))
        assertEquals(listOf(2026, 2027), years)
    }

    @Test
    fun cancelledLoadPropagatesAndCanRetry() = runBlocking {
        var loads = 0
        val cache = TaiwanHolidayCache(loadYear = {
            if (++loads == 1) throw CancellationException("cancelled")
            snapshot(2026, overrides = mapOf(date to true))
        })
        try {
            cache.isHoliday(date)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(cache.isHoliday(date))
    }

    @Test
    fun incompleteDownloadedCalendarIsNotSavedAndRetriesAfterFiveMinutes() = runBlocking {
        var now = 0L
        var calls = 0
        var saves = 0
        val partial = TaiwanHolidayCalendarSnapshot(2026, 0, 0, mapOf(date.toString() to true))
        val cache = TaiwanHolidayCache(
            loadYear = { if (++calls == 1) partial else snapshot(2026, overrides = mapOf(date to true)) },
            nowMillis = { now },
            saveYear = { saves++ }
        )
        assertFalse(cache.isHoliday(date))
        assertEquals(0, saves)
        now += 300_000L
        assertTrue(cache.isHoliday(date))
        assertEquals(1, saves)
    }

    @Test
    fun downloadedCalendarIsSavedAndUsedOnNextAppRunWithoutDownloading() = runBlocking {
        var downloads = 0
        var saved: TaiwanHolidayCalendarSnapshot? = null
        val firstRunCache = TaiwanHolidayCache(
            loadYear = { downloads++; snapshot(2026, overrides = mapOf(date to true)) },
            readSavedYear = { saved },
            saveYear = { saved = it }
        )
        assertTrue(firstRunCache.isHoliday(date))
        assertEquals(0, saved?.calendarVersion)

        val nextAppRunCache = TaiwanHolidayCache(
            loadYear = { downloads++; error("network should not be called") },
            readSavedYear = { saved }
        )
        assertTrue(nextAppRunCache.isHoliday(date))
        assertEquals(1, downloads)
    }

    @Test
    fun alternatingYearQueriesReadPersistentCalendarsOnlyOncePerYear() = runBlocking {
        var reads = 0
        val date2027 = LocalDate.of(2027, 10, 8)
        val cache = TaiwanHolidayCache(
            loadYear = { error("saved calendars should avoid downloads") },
            readSavedYear = { year ->
                reads++
                val queriedDate = if (year == 2027) date2027 else date
                snapshot(year, overrides = mapOf(queriedDate to true))
            }
        )
        assertTrue(cache.isHoliday(date))
        assertTrue(cache.isHoliday(date2027))
        assertTrue(cache.isHoliday(date))
        assertTrue(cache.isHoliday(date2027))
        assertEquals(2, reads)
    }

    @Test
    fun invalidBundledCalendarFallsBackToCdn() = runBlocking {
        var downloads = 0
        val cache = TaiwanHolidayCache(
            loadYear = { downloads++; snapshot(2026, overrides = mapOf(date to true)) },
            loadBundledYear = { error("bundled JSON is corrupt") }
        )
        assertTrue(cache.isHoliday(date))
        assertEquals(1, downloads)
    }

    @Test
    fun invalidBundledAndFailedCdnKeepLastValidSavedCalendar() = runBlocking {
        var downloads = 0
        val saved = snapshot(2026, overrides = mapOf(date to true))
        val cache = TaiwanHolidayCache(
            loadYear = { downloads++; error("CDN unavailable") },
            readSavedYear = { saved },
            loadBundledYear = { error("bundled JSON is incomplete") }
        )
        assertTrue(cache.isHoliday(date))
        assertEquals(1, downloads)
    }

    @Test
    fun newerBundledCalendarReplacesOlderSavedCalendar() = runBlocking {
        var saved = snapshot(2026, version = 1, overrides = mapOf(date to false))
        val cache = TaiwanHolidayCache(
            loadYear = { error("no network expected") },
            readSavedYear = { saved },
            saveYear = { saved = it },
            loadBundledYear = { snapshot(2026, version = 2, overrides = mapOf(date to true)) }
        )
        assertTrue(cache.isHoliday(date))
        assertEquals(2, saved.calendarVersion)
    }

    @Test
    fun missingCalendarFallsBackToWeekendOnly() = runBlocking {
        var now = 0L
        val cache = TaiwanHolidayCache(loadYear = { error("offline") }, nowMillis = { now })
        assertFalse(cache.isHoliday(date))
        assertTrue(cache.isHoliday(LocalDate.of(2026, 10, 10)))
        now += 300_000L
        assertFalse(cache.isHoliday(date))
    }

    private fun snapshot(
        year: Int,
        version: Int = 0,
        overrides: Map<LocalDate, Boolean> = emptyMap()
    ): TaiwanHolidayCalendarSnapshot {
        val first = LocalDate.of(year, 1, 1)
        val holidays = (0 until first.lengthOfYear()).associate { offset ->
            val day = first.plusDays(offset.toLong())
            day.toString() to (overrides[day] ?: (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY))
        }
        return TaiwanHolidayCalendarSnapshot(year, version, 0L, holidays, "test")
    }

    private fun sourceItems(year: Int): List<TaiwanHolidayItem> {
        val first = LocalDate.of(year, 1, 1)
        val weekdays = listOf("日", "一", "二", "三", "四", "五", "六")
        return (0 until first.lengthOfYear()).map { offset ->
            val day = first.plusDays(offset.toLong())
            TaiwanHolidayItem(
                date = day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                week = weekdays[day.dayOfWeek.value % 7],
                isHoliday = false,
                description = ""
            )
        }
    }
}
